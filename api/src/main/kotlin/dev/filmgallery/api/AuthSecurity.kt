package dev.filmgallery.api

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextHolderFilter
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.web.filter.OncePerRequestFilter

@Configuration
class AuthSecurity {
    @Bean fun passwordEncoder(): PasswordEncoder = Argon2PasswordEncoder(16, 32, 1, 19456, 2)
    @Bean fun contextRepository() = HttpSessionSecurityContextRepository()
    @Bean fun csrfRepository() = HttpSessionCsrfTokenRepository()
    @Bean fun userDetailsService(): UserDetailsService = UserDetailsService { throw UsernameNotFoundException("Use the JSON login endpoint") }
    @Bean fun security(http: HttpSecurity, service: AuthService, repository: HttpSessionSecurityContextRepository, csrf: HttpSessionCsrfTokenRepository): SecurityFilterChain {
        http.securityContext { it.securityContextRepository(repository) }
            .csrf { it.csrfTokenRepository(csrf) }
            .authorizeHttpRequests {
                it.requestMatchers("/api/auth/csrf", "/api/auth/login", "/api/auth/signup/request", "/api/auth/signup/complete", "/api/auth/reset/request", "/api/auth/reset/complete", "/api/stack", "/actuator/health").permitAll()
                    .requestMatchers("/api/me", "/api/photo-settings", "/api/shoot-sessions", "/api/shoot-sessions/**", "/api/photos/**").authenticated().anyRequest().denyAll()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ -> error(response, 401, "로그인이 필요합니다.") }
                    .accessDeniedHandler { _, response, _ -> error(response, 403, "요청을 확인할 수 없습니다. 새로고침 후 다시 시도해 주세요.") }
            }
            .logout { it.logoutUrl("/api/auth/logout").deleteCookies("SESSION").logoutSuccessHandler { _, response, _ -> response.status = 204 } }
            .addFilterAfter(object : OncePerRequestFilter() {
                override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
                    val auth = SecurityContextHolder.getContext().authentication
                    if (auth != null && auth.isAuthenticated) {
                        val session = request.getSession(false)
                        val version = session?.getAttribute("AUTH_VERSION") as? Int
                        if (version == null || !service.validSession(auth.name, version)) {
                            session?.invalidate()
                            SecurityContextHolder.clearContext()
                        }
                    }
                    chain.doFilter(request, response)
                }
            }, SecurityContextHolderFilter::class.java)
        return http.build()
    }
    private fun error(response: HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write("{\"message\":\"$message\"}")
    }
}
