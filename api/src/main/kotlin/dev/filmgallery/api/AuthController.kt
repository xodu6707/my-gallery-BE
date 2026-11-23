package dev.filmgallery.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.mail.MailException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.*

class EmailRequest(@field:NotBlank @field:Size(max = 254) val email: String)
class LoginRequest(@field:NotBlank @field:Size(max = 254) val email: String, @field:NotBlank @field:Size(max = 128) val password: String)
class CompleteRequest(@field:NotBlank @field:Size(max = 64) val token: String, @field:NotBlank @field:Size(max = 128) val password: String)

@RestController
class AuthController(private val service: AuthService, private val repository: HttpSessionSecurityContextRepository, private val csrfRepository: HttpSessionCsrfTokenRepository) {
    @GetMapping("/api/auth/csrf") fun csrf(token: CsrfToken) = mapOf("headerName" to token.headerName, "token" to token.token)
    @GetMapping("/api/me") fun me(auth: Authentication) = service.current(auth.name)
    @PostMapping("/api/auth/login") fun login(@Valid @RequestBody body: LoginRequest, request: HttpServletRequest, response: HttpServletResponse): Map<String, String> {
        val user = service.authenticate(body.email, body.password, request.remoteAddr)
        val auth = UsernamePasswordAuthenticationToken.authenticated(user.id.toString(), null, listOf(SimpleGrantedAuthority("ROLE_OWNER")))
        ChangeSessionIdAuthenticationStrategy().onAuthentication(auth, request, response)
        csrfRepository.saveToken(null, request, response)
        val context = SecurityContextHolder.createEmptyContext().also { it.authentication = auth }
        SecurityContextHolder.setContext(context)
        request.getSession(true).setAttribute("AUTH_VERSION", user.version)
        repository.saveContext(context, request, response)
        return service.current(user.id.toString())
    }
    @PostMapping("/api/auth/signup/request") fun signup(@Valid @RequestBody body: EmailRequest, request: HttpServletRequest) = send(body.email, "signup", request)
    @PostMapping("/api/auth/reset/request") fun reset(@Valid @RequestBody body: EmailRequest, request: HttpServletRequest) = send(body.email, "reset", request)
    private fun send(email: String, purpose: String, request: HttpServletRequest): ResponseEntity<Map<String, String>> {
        service.requestLink(email, purpose, request.remoteAddr)
        return ResponseEntity.accepted().body(mapOf("message" to "가입 또는 재설정이 가능한 주소라면 인증 메일을 보냈습니다. 메일함을 확인해 주세요."))
    }
    @PostMapping("/api/auth/signup/complete") fun verify(@Valid @RequestBody body: CompleteRequest, request: HttpServletRequest) = complete(body, "signup", request)
    @PostMapping("/api/auth/reset/complete") fun change(@Valid @RequestBody body: CompleteRequest, request: HttpServletRequest) = complete(body, "reset", request)
    private fun complete(body: CompleteRequest, purpose: String, request: HttpServletRequest): ResponseEntity<Void> {
        service.complete(body.token, body.password, purpose, request.remoteAddr)
        return ResponseEntity.noContent().build()
    }
}

@RestControllerAdvice
class AuthErrors {
    @ExceptionHandler(AuthProblem::class) fun problem(error: AuthProblem) = ResponseEntity.status(error.status).body(mapOf("message" to error.message))
    @ExceptionHandler(MethodArgumentNotValidException::class) fun invalid() = ResponseEntity.badRequest().body(mapOf("message" to "입력한 내용을 확인해 주세요."))
    @ExceptionHandler(MailException::class) fun mail() = ResponseEntity.status(503).body(mapOf("message" to "메일을 보내지 못했습니다. 잠시 후 다시 요청해 주세요."))
}
