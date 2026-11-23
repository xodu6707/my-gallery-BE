package dev.filmgallery.api

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

class PhotoProblem(val status: Int, val code: String, override val message: String) : RuntimeException(message)
fun missing(): Nothing = throw PhotoProblem(404, "NOT_FOUND", "사진 또는 촬영 세션을 찾을 수 없습니다.")
fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

@Service
class PhotoService(val jdbc: JdbcTemplate, private val storage: StoragePort, private val mapper: ObjectMapper,
                   @Value("\${gallery.photos.max-bytes}") val maxBytes: Long) {
    fun session(owner: UUID, id: UUID, lock: Boolean = false): Map<String, Any?> = jdbc.queryForList(
        "SELECT * FROM shoot_sessions WHERE id=? AND owner_id=? AND deleted_at IS NULL" + if (lock) " FOR UPDATE" else "", id, owner).firstOrNull() ?: missing()
    fun photo(owner: UUID, id: UUID, lock: Boolean = false): Map<String, Any?> = jdbc.queryForList(
        "SELECT * FROM photos WHERE id=? AND owner_id=? AND deleted_at IS NULL" + if (lock) " FOR UPDATE" else "", id, owner).firstOrNull() ?: missing()
    private fun title(value: String): String = value.trim().also {
        if (it.isEmpty() || it.length > 120) throw PhotoProblem(400, "INVALID_TITLE", "제목은 1~120자로 입력해 주세요.")
    }
    fun sessionView(row: Map<String, Any?>): Map<String, Any?> = mapOf(
        "id" to row["id"], "title" to row["title"], "shootDate" to row["shoot_date"].toString(),
        "coverPhotoId" to row["cover_photo_id"], "photoCount" to jdbc.queryForObject("SELECT count(*) FROM photos WHERE session_id=? AND deleted_at IS NULL", Int::class.java, row["id"]))
    fun list(owner: UUID, cursor: String?): Map<String, Any?> {
        val rows = if (cursor.isNullOrEmpty()) jdbc.queryForList("SELECT * FROM shoot_sessions WHERE owner_id=? AND deleted_at IS NULL ORDER BY shoot_date DESC,id DESC LIMIT 31", owner)
        else {
            val parts = cursor.split("_")
            if (parts.size != 2) throw PhotoProblem(400, "INVALID_CURSOR", "목록 위치가 올바르지 않습니다.")
            val date = LocalDate.parse(parts[0]); val id = UUID.fromString(parts[1])
            jdbc.queryForList("SELECT * FROM shoot_sessions WHERE owner_id=? AND deleted_at IS NULL AND (shoot_date,id)<(?,?) ORDER BY shoot_date DESC,id DESC LIMIT 31", owner, date, id)
        }
        val items = rows.take(30)
        return mapOf("items" to items.map(::sessionView), "nextCursor" to if (rows.size > 30) "${items.last()["shoot_date"]}_${items.last()["id"]}" else null)
    }
    @Transactional fun create(owner: UUID, title: String, date: LocalDate): Map<String, Any?> {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO shoot_sessions(id,owner_id,title,shoot_date) VALUES (?,?,?,?)", id, owner, title(title), date)
        return sessionView(session(owner, id))
    }
    @Transactional fun update(owner: UUID, id: UUID, title: String, date: LocalDate, cover: UUID?): Map<String, Any?> {
        session(owner, id, true)
        if (cover != null && jdbc.queryForObject("SELECT count(*) FROM photos WHERE id=? AND session_id=? AND owner_id=? AND status='READY' AND deleted_at IS NULL", Int::class.java, cover, id, owner) != 1) missing()
        jdbc.update("UPDATE shoot_sessions SET title=?,shoot_date=?,cover_photo_id=? WHERE id=?", title(title), date, cover, id)
        return sessionView(session(owner, id))
    }
    fun photoView(row: Map<String, Any?>): Map<String, Any?> = mapOf(
        "id" to row["id"], "sessionId" to row["session_id"], "filename" to row["filename"], "size" to row["expected_size"],
        "status" to row["status"], "sortOrder" to row["sort_order"], "errorCode" to row["error_code"],
        "metadata" to row["metadata"]?.let { mapper.readValue(it.toString(), Map::class.java) },
        "thumbnailUrl" to if (row["status"] == "READY") "/api/photos/${row["id"]}/image/thumbnail" else null,
        "viewUrl" to if (row["status"] == "READY") "/api/photos/${row["id"]}/image/view" else null)
    fun photos(owner: UUID, id: UUID, cursor: String?): Map<String, Any?> {
        val parent = session(owner, id)
        val rows = if (cursor.isNullOrEmpty()) jdbc.queryForList("SELECT * FROM photos WHERE session_id=? AND owner_id=? AND deleted_at IS NULL ORDER BY sort_order,id LIMIT 61", id, owner)
        else {
            val parts = cursor.split("_")
            if (parts.size != 2) throw PhotoProblem(400, "INVALID_CURSOR", "목록 위치가 올바르지 않습니다.")
            jdbc.queryForList("SELECT * FROM photos WHERE session_id=? AND owner_id=? AND deleted_at IS NULL AND (sort_order,id)>(?,?) ORDER BY sort_order,id LIMIT 61", id, owner, parts[0].toInt(), UUID.fromString(parts[1]))
        }
        val items = rows.take(60)
        return mapOf("session" to sessionView(parent), "items" to items.map(::photoView), "nextCursor" to if (rows.size > 60) "${items.last()["sort_order"]}_${items.last()["id"]}" else null)
    }
    @Transactional fun register(owner: UUID, sessionId: UUID, requestId: UUID, filename: String, size: Long): Map<String, Any?> {
        session(owner, sessionId, true)
        if (size !in 1..maxBytes) throw PhotoProblem(413, "FILE_TOO_LARGE", "사진은 파일당 ${maxBytes / 1048576} MiB 이하로 올려 주세요.")
        if (filename.length !in 1..255 || !filename.lowercase().matches(Regex(".*\\.jpe?g$"))) throw PhotoProblem(400, "JPEG_ONLY", "JPEG 파일만 업로드할 수 있습니다.")
        val previous = jdbc.queryForList("SELECT * FROM photos WHERE owner_id=? AND request_id=?", owner, requestId).firstOrNull()
        if (previous != null) {
            if (previous["session_id"] != sessionId || previous["filename"] != filename || (previous["expected_size"] as Number).toLong() != size || previous["deleted_at"] != null) throw PhotoProblem(409, "REQUEST_CONFLICT", "업로드 요청이 변경되었습니다. 파일을 다시 선택해 주세요.")
            return grant(previous)
        }
        val id = UUID.randomUUID(); val key = "$owner/$id/original.jpg"
        val position = jdbc.queryForObject("SELECT COALESCE(max(sort_order),-1)+1 FROM photos WHERE session_id=?", Int::class.java, sessionId)!!
        jdbc.update("INSERT INTO photos(id,owner_id,session_id,request_id,filename,expected_size,original_key,status,sort_order) VALUES (?,?,?,?,?,?,?,'UPLOADING',?)", id, owner, sessionId, requestId, filename, size, key, position)
        jdbc.update("INSERT INTO storage_garbage VALUES (?, now()+interval '24 hours')", key)
        return grant(photo(owner, id))
    }
    private fun grant(row: Map<String, Any?>) = mapOf("photo" to photoView(row), "uploadUrl" to storage.uploadPath(row["id"].toString()), "maxBytes" to maxBytes)
    @Transactional fun upload(owner: UUID, id: UUID, bytes: ByteArray) {
        val row = photo(owner, id, true)
        if (row["status"] != "UPLOADING") throw PhotoProblem(409, "IMMUTABLE_ORIGINAL", "업로드가 완료된 원본은 덮어쓸 수 없습니다.")
        if (bytes.size.toLong() != (row["expected_size"] as Number).toLong() || bytes.size > maxBytes) throw PhotoProblem(400, "SIZE_MISMATCH", "등록한 파일 크기와 실제 파일이 다릅니다.")
        if (bytes.size < 4 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte() || bytes[2] != 0xff.toByte()) throw PhotoProblem(400, "INVALID_JPEG", "실제 JPEG 파일이 아닙니다.")
        val hash = sha256(bytes); val key = row["original_key"].toString()
        if (storage.size(key) != null) {
            if (sha256(storage.get(key)) != hash) throw PhotoProblem(409, "IMMUTABLE_ORIGINAL", "원본 파일을 변경할 수 없습니다. 새 파일로 등록해 주세요.")
        } else storage.put(key, bytes)
        jdbc.update("UPDATE photos SET original_sha256=? WHERE id=?", hash, id)
    }
    @Transactional fun complete(owner: UUID, id: UUID): Map<String, Any?> {
        val row = photo(owner, id, true)
        if (row["status"] != "UPLOADING") return photoView(row)
        val size = storage.size(row["original_key"].toString())
        if (row["original_sha256"] == null || size != (row["expected_size"] as Number).toLong()) throw PhotoProblem(409, "UPLOAD_INCOMPLETE", "파일 전송이 완료되지 않았습니다. 다시 업로드해 주세요.")
        jdbc.update("UPDATE photos SET status='QUEUED' WHERE id=?", id)
        jdbc.update("INSERT INTO processing_jobs(photo_id) VALUES (?) ON CONFLICT DO NOTHING", id)
        return photoView(photo(owner, id))
    }
    @Transactional fun retry(owner: UUID, id: UUID): Map<String, Any?> {
        val row = photo(owner, id, true)
        if (row["status"] != "FAILED") return photoView(row)
        jdbc.update("UPDATE photos SET status='QUEUED',error_code=NULL WHERE id=?", id)
        jdbc.update("UPDATE processing_jobs SET attempts=0,finished=false,next_at=now(),lease_id=NULL,lease_until=NULL WHERE photo_id=?", id)
        return photoView(photo(owner, id))
    }
    @Transactional fun reorder(owner: UUID, sessionId: UUID, ids: List<UUID>) {
        session(owner, sessionId, true)
        val existing = jdbc.query("SELECT id FROM photos WHERE session_id=? AND deleted_at IS NULL", { rs, _ -> rs.getObject(1, UUID::class.java) }, sessionId)
        if (ids.size != ids.toSet().size || ids.toSet() != existing.toSet()) throw PhotoProblem(409, "ORDER_CHANGED", "사진 목록이 변경됐습니다. 새로고침 후 순서를 바꿔 주세요.")
        ids.forEachIndexed { index, id -> jdbc.update("UPDATE photos SET sort_order=? WHERE id=?", index, id) }
    }
    private fun erase(row: Map<String, Any?>) {
        jdbc.update("UPDATE photos SET status='DELETED',deleted_at=now() WHERE id=?", row["id"])
        jdbc.update("UPDATE processing_jobs SET finished=true,lease_id=NULL WHERE photo_id=?", row["id"])
        jdbc.update("UPDATE shoot_sessions SET cover_photo_id=NULL WHERE cover_photo_id=?", row["id"])
        listOf("original_key", "thumb_key", "view_key").mapNotNull { row[it] }.forEach {
            jdbc.update("INSERT INTO storage_garbage VALUES (?,now()) ON CONFLICT(object_key) DO UPDATE SET not_before=now()", it)
        }
    }
    @Transactional fun deletePhoto(owner: UUID, id: UUID) {
        val initial = photo(owner, id)
        session(owner, initial["session_id"] as UUID, true)
        erase(photo(owner, id, true))
    }
    @Transactional fun deleteSession(owner: UUID, id: UUID) {
        session(owner, id, true)
        jdbc.queryForList("SELECT * FROM photos WHERE session_id=? AND deleted_at IS NULL FOR UPDATE", id).forEach(::erase)
        jdbc.update("UPDATE shoot_sessions SET deleted_at=now(),cover_photo_id=NULL WHERE id=?", id)
    }
    fun image(owner: UUID, id: UUID, variant: String): ByteArray {
        val row = photo(owner, id)
        val column = when(variant) { "thumbnail" -> "thumb_key"; "view" -> "view_key"; "original" -> "original_key"; else -> missing() }
        if (row["status"] != "READY" || row[column] == null) missing()
        val bytes = storage.get(row[column].toString())
        photo(owner, id) // Recheck a deletion that happened during the storage read.
        return bytes
    }
}
