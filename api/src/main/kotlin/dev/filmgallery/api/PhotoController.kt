package dev.filmgallery.api

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.multipart.MaxUploadSizeExceededException
import java.time.LocalDate
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.core.Ordered
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.http.converter.HttpMessageNotReadableException

class ShootRequest(@field:NotBlank @field:Size(max=120) val title: String, val shootDate: LocalDate, val coverPhotoId: UUID? = null)
class UploadRequest(val requestId: UUID, @field:NotBlank @field:Size(max=255) val filename: String, val size: Long)
class OrderRequest(@field:Size(max=10000) val photoIds: List<UUID>)

@RestController
class PhotoController(private val service: PhotoService) {
    private fun owner(auth: Authentication) = UUID.fromString(auth.name)
    @GetMapping("/api/photo-settings") fun settings() = mapOf("maxBytes" to service.maxBytes, "concurrentUploads" to 3, "acceptedTypes" to listOf("image/jpeg"))
    @GetMapping("/api/shoot-sessions") fun sessions(auth: Authentication, @RequestParam(required=false) cursor: String?) = service.list(owner(auth), cursor)
    @PostMapping("/api/shoot-sessions") fun create(auth: Authentication, @Valid @RequestBody body: ShootRequest) = ResponseEntity.status(201).body(service.create(owner(auth), body.title, body.shootDate))
    @PatchMapping("/api/shoot-sessions/{id}") fun update(auth: Authentication, @PathVariable id: UUID, @Valid @RequestBody body: ShootRequest) = service.update(owner(auth), id, body.title, body.shootDate, body.coverPhotoId)
    @DeleteMapping("/api/shoot-sessions/{id}") fun deleteSession(auth: Authentication, @PathVariable id: UUID): ResponseEntity<Void> {
        service.deleteSession(owner(auth), id); return ResponseEntity.noContent().build()
    }
    @GetMapping("/api/shoot-sessions/{id}/photos") fun photos(auth: Authentication, @PathVariable id: UUID, @RequestParam(required=false) cursor: String?) = service.photos(owner(auth), id, cursor)
    @PostMapping("/api/shoot-sessions/{id}/uploads") fun register(auth: Authentication, @PathVariable id: UUID, @Valid @RequestBody body: UploadRequest) = ResponseEntity.status(201).body(service.register(owner(auth), id, body.requestId, body.filename, body.size))
    @PutMapping("/api/shoot-sessions/{id}/photo-order") fun order(auth: Authentication, @PathVariable id: UUID, @Valid @RequestBody body: OrderRequest): ResponseEntity<Void> {
        service.reorder(owner(auth), id, body.photoIds); return ResponseEntity.noContent().build()
    }
    @GetMapping("/api/photos/{id}") fun photo(auth: Authentication, @PathVariable id: UUID) = service.photoView(service.photo(owner(auth), id))
    @PutMapping("/api/photos/{id}/content", consumes=["multipart/form-data"]) fun upload(auth: Authentication, @PathVariable id: UUID, @RequestPart("file") file: MultipartFile): ResponseEntity<Void> {
        if (file.size > service.maxBytes) throw PhotoProblem(413, "FILE_TOO_LARGE", "파일 크기 제한을 초과했습니다.")
        service.upload(owner(auth), id, file.bytes); return ResponseEntity.noContent().build()
    }
    @PostMapping("/api/photos/{id}/complete") fun complete(auth: Authentication, @PathVariable id: UUID) = service.complete(owner(auth), id)
    @PostMapping("/api/photos/{id}/retry") fun retry(auth: Authentication, @PathVariable id: UUID) = service.retry(owner(auth), id)
    @DeleteMapping("/api/photos/{id}") fun delete(auth: Authentication, @PathVariable id: UUID): ResponseEntity<Void> {
        service.deletePhoto(owner(auth), id); return ResponseEntity.noContent().build()
    }
    @GetMapping("/api/photos/{id}/image/{variant}") fun image(auth: Authentication, @PathVariable id: UUID, @PathVariable variant: String): ResponseEntity<ByteArray> = ResponseEntity.ok()
        .header("Content-Type", "image/jpeg").header("Cache-Control", "private, no-store")
        .header("Content-Disposition", if (variant == "original") "attachment; filename=\"$id.jpg\"" else "inline")
        .body(service.image(owner(auth), id, variant))
}

@RestControllerAdvice(assignableTypes = [PhotoController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class PhotoErrors {
    @ExceptionHandler(PhotoProblem::class) fun problem(error: PhotoProblem): ResponseEntity<Map<String,String>> {
        val traceId = UUID.randomUUID().toString()
        LoggerFactory.getLogger(PhotoErrors::class.java).warn("Photo request failed: traceId={}, code={}, status={}", traceId, error.code, error.status)
        return ResponseEntity.status(error.status).body(mapOf("code" to error.code, "message" to error.message, "traceId" to traceId))
    }
    @ExceptionHandler(MaxUploadSizeExceededException::class) fun size() = problem(PhotoProblem(413, "FILE_TOO_LARGE", "파일 크기 제한을 초과했습니다."))
    @ExceptionHandler(IllegalArgumentException::class, MethodArgumentNotValidException::class, MethodArgumentTypeMismatchException::class, HttpMessageNotReadableException::class) fun invalid() = problem(PhotoProblem(400, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다."))
    @ExceptionHandler(IllegalStateException::class, java.io.IOException::class) fun unavailable() = problem(PhotoProblem(503, "STORAGE_UNAVAILABLE", "사진 저장소에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요."))
}
