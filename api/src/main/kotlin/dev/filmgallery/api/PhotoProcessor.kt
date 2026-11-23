package dev.filmgallery.api

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID

@Service
class PhotoProcessor(private val jdbc: JdbcTemplate, private val tx: TransactionTemplate,
                     private val storage: StoragePort, private val mapper: ObjectMapper,
                     @Value("\${gallery.ingest.url}") private val endpoint: String,
                     @Value("\${gallery.ingest.token}") private val token: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    @Scheduled(fixedDelayString = "\${gallery.photos.poll-ms:2000}")
    fun processNext() {
        val lease = UUID.randomUUID()
        val row = tx.execute {
            val item = jdbc.queryForList("""
              SELECT p.*,j.attempts AS job_attempts FROM processing_jobs j JOIN photos p ON p.id=j.photo_id
              WHERE NOT j.finished AND j.next_at<=now() AND (j.lease_until IS NULL OR j.lease_until<now())
              AND p.deleted_at IS NULL ORDER BY j.next_at LIMIT 1 FOR UPDATE OF j,p SKIP LOCKED
            """).firstOrNull() ?: return@execute null
            if ((item["job_attempts"] as Number).toInt() >= 3) {
                jdbc.update("UPDATE processing_jobs SET finished=true,lease_id=NULL WHERE photo_id=?", item["id"])
                jdbc.update("UPDATE photos SET status='FAILED',error_code='INGEST_UNAVAILABLE' WHERE id=?", item["id"])
                return@execute null
            }
            jdbc.update("UPDATE processing_jobs SET attempts=attempts+1,lease_id=?,lease_until=now()+interval '5 minutes' WHERE photo_id=?", lease, item["id"])
            jdbc.update("UPDATE photos SET status='PROCESSING' WHERE id=?", item["id"])
            item
        } ?: return
        val id = row["id"]!!
        val base = "${row["owner_id"]}/$id/$lease"
        val thumbKey = "$base/thumbnail.jpg"; val viewKey = "$base/view.jpg"
        try {
            // Track every planned object before writing; a crash cannot create an untracked orphan.
            listOf(thumbKey, viewKey).forEach { jdbc.update("INSERT INTO storage_garbage VALUES (?,now()+interval '1 hour') ON CONFLICT DO NOTHING", it) }
            val source = storage.get(row["original_key"].toString())
            if (sha256(source) != row["original_sha256"]) { fail(id, lease, "ORIGINAL_CHANGED", false); return }
            val request = HttpRequest.newBuilder(URI.create("${endpoint.trimEnd('/')}/process"))
                .timeout(Duration.ofSeconds(120)).header("Authorization", "Bearer $token").header("Content-Type", "image/jpeg")
                .POST(HttpRequest.BodyPublishers.ofByteArray(source)).build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                val code = if (response.statusCode() == 422) mapper.readTree(response.body()).path("code").asText() else "INGEST_UNAVAILABLE"
                fail(id, lease, if (code in setOf("INVALID_JPEG", "PIXEL_LIMIT", "DECODE_FAILED")) code else "INGEST_UNAVAILABLE", response.statusCode() != 422)
                return
            }
            val result = mapper.readTree(response.body())
            storage.put(thumbKey, Base64.getDecoder().decode(result.path("thumbnail").asText()))
            storage.put(viewKey, Base64.getDecoder().decode(result.path("view").asText()))
            tx.executeWithoutResult {
                val current = jdbc.queryForList("SELECT p.id FROM photos p JOIN processing_jobs j ON p.id=j.photo_id WHERE p.id=? AND p.deleted_at IS NULL AND NOT j.finished AND j.lease_id=? FOR UPDATE OF p,j", id, lease)
                if (current.isEmpty()) return@executeWithoutResult
                jdbc.update("UPDATE photos SET status='READY',metadata=?::jsonb,thumb_key=?,view_key=?,pipeline_version=?,error_code=NULL WHERE id=?", result.path("metadata").toString(), thumbKey, viewKey, result.path("pipelineVersion").asText(), id)
                jdbc.update("UPDATE processing_jobs SET finished=true,lease_until=NULL WHERE photo_id=? AND lease_id=?", id, lease)
            }
        } catch (_: Exception) { fail(id, lease, "INGEST_UNAVAILABLE", true) }
    }
    private fun fail(id: Any, lease: UUID, code: String, retryable: Boolean) {
        tx.executeWithoutResult {
            val current = jdbc.queryForList("SELECT j.attempts FROM processing_jobs j JOIN photos p ON p.id=j.photo_id WHERE p.id=? AND p.deleted_at IS NULL AND NOT j.finished AND j.lease_id=? FOR UPDATE OF j,p", id, lease).firstOrNull() ?: return@executeWithoutResult
            val retry = retryable && (current["attempts"] as Number).toInt() < 3
            jdbc.update("UPDATE photos SET status=?,error_code=? WHERE id=?", if (retry) "QUEUED" else "FAILED", code, id)
            jdbc.update("UPDATE processing_jobs SET finished=?,lease_id=NULL,lease_until=NULL,next_at=now()+interval '15 seconds' WHERE photo_id=?", !retry, id)
        }
    }
    @Scheduled(fixedDelayString = "\${gallery.photos.cleanup-ms:30000}")
    fun cleanup() {
        // Resolve lost completion notifications using verified, immutable uploads.
        jdbc.update("""INSERT INTO processing_jobs(photo_id) SELECT id FROM photos WHERE status='UPLOADING' AND original_sha256 IS NOT NULL AND created_at<now()-interval '10 minutes' ON CONFLICT DO NOTHING""")
        jdbc.update("UPDATE photos SET status='QUEUED' WHERE status='UPLOADING' AND original_sha256 IS NOT NULL AND created_at<now()-interval '10 minutes'")
        jdbc.update("""WITH expired AS (
          UPDATE photos SET status='DELETED',deleted_at=now() WHERE status='UPLOADING' AND original_sha256 IS NULL AND created_at<now()-interval '24 hours' RETURNING original_key
        ) INSERT INTO storage_garbage SELECT original_key,now() FROM expired ON CONFLICT(object_key) DO UPDATE SET not_before=now()""")
        jdbc.queryForList("SELECT object_key FROM storage_garbage WHERE not_before<=now() ORDER BY not_before LIMIT 30").forEach { row ->
            val key = row["object_key"] as String
            try {
                tx.executeWithoutResult {
                    val locked = jdbc.queryForList("SELECT object_key FROM storage_garbage WHERE object_key=? AND not_before<=now() FOR UPDATE SKIP LOCKED", key)
                    if (locked.isEmpty()) return@executeWithoutResult
                    val references = jdbc.queryForObject("SELECT count(*) FROM photos WHERE deleted_at IS NULL AND (original_key=? OR thumb_key=? OR view_key=?)", Int::class.java, key, key, key)
                    if (references == 0) storage.delete(key)
                    jdbc.update("DELETE FROM storage_garbage WHERE object_key=?", key)
                }
            } catch (_: Exception) { /* Keep the cleanup record for the next run. */ }
        }
    }
}
