package dev.filmgallery.api

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID

class AuthProblem(val status: Int, override val message: String) : RuntimeException(message)
data class Account(val id: UUID, val email: String, val hash: String, val version: Int)
fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

@Service
class AuthLimiter(private val jdbc: JdbcTemplate) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun take(key: String, limit: Int, seconds: Int): Boolean {
        jdbc.update("DELETE FROM auth_rate_limits WHERE expires_at < CURRENT_TIMESTAMP")
        val hits = jdbc.queryForObject("""
          INSERT INTO auth_rate_limits(bucket,hits,expires_at) VALUES (?,1,CURRENT_TIMESTAMP + make_interval(secs => ?))
          ON CONFLICT(bucket) DO UPDATE SET hits=auth_rate_limits.hits+1 RETURNING hits
        """, Int::class.java, digest(key), seconds)!!
        return hits <= limit
    }
}

@Service
class AuthService(
    private val jdbc: JdbcTemplate, private val encoder: PasswordEncoder, private val mail: JavaMailSender,
    private val limiter: AuthLimiter,
    @Value("\${gallery.auth.owner-email}") private val owner: String,
    @Value("\${gallery.auth.registration-open}") private val registrationOpen: Boolean,
    @Value("\${gallery.auth.public-url}") private val publicUrl: String,
    @Value("\${gallery.auth.mail-from}") private val mailFrom: String,
) {
    private val random = SecureRandom()
    private val dummyHash = encoder.encode(UUID.randomUUID().toString())
    private fun limit(key: String, maximum: Int, seconds: Int) {
        if (!limiter.take(key, maximum, seconds)) throw AuthProblem(429, "요청이 많습니다. 잠시 후 다시 시도해 주세요.")
    }
    private fun lockEmail(email: String) {
        // Serialize issuance/redemption for one account, including different reset links.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", { _, _ -> 0 }, email)
    }
    fun normalize(email: String): String {
        val normalized = email.trim().lowercase(Locale.ROOT)
        if (normalized.length > 254 || !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(normalized))
            throw AuthProblem(400, "올바른 이메일 주소를 입력해 주세요.")
        return normalized
    }
    fun account(email: String): Account? = jdbc.query("SELECT * FROM auth_users WHERE email=?", { rs, _ ->
        Account(rs.getObject("id", UUID::class.java), rs.getString("email"), rs.getString("password_hash"), rs.getInt("version"))
    }, email).firstOrNull()
    fun validSession(id: String, version: Int): Boolean = jdbc.queryForObject(
        "SELECT count(*) FROM auth_users WHERE id::text=? AND version=?", Int::class.java, id, version) == 1
    fun current(id: String) = jdbc.query("SELECT id,email FROM auth_users WHERE id::text=?", { rs, _ ->
        mapOf("id" to rs.getString("id"), "email" to rs.getString("email"), "role" to "OWNER")
    }, id).firstOrNull() ?: throw AuthProblem(401, "로그인이 필요합니다.")
    fun password(value: String) {
        if (value.length !in 15..128) throw AuthProblem(400, "비밀번호는 15~128자로 입력해 주세요.")
    }
    fun authenticate(email: String, password: String, ip: String): Account {
        val normalized = normalize(email)
        limit("login-ip:$ip", 100, 600)
        limit("login:$ip:$normalized", 10, 600)
        if (password.length > 128) throw AuthProblem(401, "이메일 또는 비밀번호를 확인해 주세요.")
        val user = account(normalized)
        val matches = encoder.matches(password, user?.hash ?: dummyHash)
        if (user == null || !matches) throw AuthProblem(401, "이메일 또는 비밀번호를 확인해 주세요.")
        return user
    }
    @Transactional
    fun requestLink(email: String, purpose: String, ip: String) {
        val normalized = normalize(email)
        limit("mail-ip:$ip", 30, 3600)
        limit("mail:$purpose:$normalized", 5, 3600)
        lockEmail(normalized)
        val user = account(normalized)
        if (purpose == "signup" && (!registrationOpen || owner.isBlank() || normalized != owner.trim().lowercase(Locale.ROOT) || user != null)) return
        if (purpose == "reset" && user == null) return
        jdbc.update("DELETE FROM auth_tokens WHERE expires_at < CURRENT_TIMESTAMP")
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        jdbc.update("INSERT INTO auth_tokens(digest,email,purpose,expires_at) VALUES (?,?,?,CURRENT_TIMESTAMP + interval '20 minutes')", digest(token), normalized, purpose)
        val path = if (purpose == "signup") "verify-email" else "reset-password"
        val message = SimpleMailMessage().apply {
            setFrom(mailFrom); setTo(normalized)
            subject = if (purpose == "signup") "A6400 이메일 인증" else "A6400 비밀번호 재설정"
            text = "아래 링크에서 직접 비밀번호를 설정해 주세요. 링크는 20분간 한 번만 사용할 수 있습니다.\n\n${publicUrl.trimEnd('/')}/$path#token=$token\n\n직접 요청하지 않았다면 이 메일을 무시하세요."
        }
        mail.send(message)
    }
    @Transactional
    fun complete(token: String, password: String, purpose: String, ip: String) {
        limit("token:$ip", 30, 600)
        password(password)
        if (!Regex("^[A-Za-z0-9_-]{43}$").matches(token)) throw AuthProblem(400, "인증 링크가 만료되었거나 이미 사용되었습니다.")
        val candidate = jdbc.query("SELECT email FROM auth_tokens WHERE digest=? AND purpose=? AND expires_at>CURRENT_TIMESTAMP", { rs, _ -> rs.getString(1) }, digest(token), purpose).firstOrNull()
            ?: throw AuthProblem(400, "인증 링크가 만료되었거나 이미 사용되었습니다.")
        lockEmail(candidate)
        val email = jdbc.query("DELETE FROM auth_tokens WHERE digest=? AND purpose=? AND expires_at>CURRENT_TIMESTAMP RETURNING email", { rs, _ -> rs.getString(1) }, digest(token), purpose).firstOrNull()
            ?: throw AuthProblem(400, "인증 링크가 만료되었거나 이미 사용되었습니다.")
        if (purpose == "signup") {
            if (!registrationOpen || email != owner.trim().lowercase(Locale.ROOT)) throw AuthProblem(403, "현재 가입할 수 없습니다.")
            val inserted = jdbc.update("INSERT INTO auth_users(id,email,password_hash) VALUES (?,?,?) ON CONFLICT(email) DO NOTHING", UUID.randomUUID(), email, encoder.encode(password))
            if (inserted != 1) throw AuthProblem(400, "이미 가입된 계정입니다. 로그인해 주세요.")
        } else {
            jdbc.update("UPDATE auth_users SET password_hash=?,version=version+1 WHERE email=?", encoder.encode(password), email)
            jdbc.update("DELETE FROM spring_session WHERE principal_name=(SELECT id::text FROM auth_users WHERE email=?)", email)
        }
        jdbc.update("DELETE FROM auth_tokens WHERE email=?", email)
    }
}
