package dev.filmgallery.api

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

interface StoragePort {
    fun uploadPath(photoId: String): String
    fun put(key: String, bytes: ByteArray)
    fun get(key: String): ByteArray
    fun size(key: String): Long?
    fun delete(key: String)
}

// Local emulator only. Production OCI authorization belongs in a separate adapter.
@Component
class S3MockStorage(@Value("\${gallery.storage.endpoint}") private val endpoint: String,
                    @Value("\${gallery.storage.bucket}") private val bucket: String) : StoragePort {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    override fun uploadPath(photoId: String) = "/api/photos/$photoId/content"
    private fun request(key: String): HttpRequest.Builder {
        require(Regex("^[a-zA-Z0-9/-]+\\.jpg$").matches(key))
        return HttpRequest.newBuilder(URI.create("${endpoint.trimEnd('/')}/$bucket/$key")).timeout(Duration.ofSeconds(30))
    }
    override fun put(key: String, bytes: ByteArray) {
        val response = http.send(request(key).header("Content-Type", "image/jpeg").PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() in 200..299) { "Storage write failed" }
    }
    override fun get(key: String): ByteArray {
        val response = http.send(request(key).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() == 200) { "Storage read failed" }
        return response.body()
    }
    override fun size(key: String): Long? {
        val response = http.send(request(key).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() == 404) return null
        check(response.statusCode() == 200) { "Storage lookup failed" }
        return response.headers().firstValueAsLong("Content-Length").orElseThrow()
    }
    override fun delete(key: String) {
        val response = http.send(request(key).DELETE().build(), HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() in 200..299 || response.statusCode() == 404) { "Storage delete failed" }
    }
}
