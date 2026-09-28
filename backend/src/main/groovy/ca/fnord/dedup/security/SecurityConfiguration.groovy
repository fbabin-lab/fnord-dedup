package ca.fnord.dedup.security

import ca.fnord.dedup.operations.AuditService
import groovy.transform.CompileStatic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.csrf.CookieCsrfTokenRepository

@CompileStatic
@Configuration
class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { new BCryptPasswordEncoder(12) }

    @Bean UserDetailsService users(SecurityProperties properties, PasswordEncoder encoder) {
        validateCredentials(properties, encoder)
        new InMemoryUserDetailsManager(User.withUsername(properties.username).password(properties.passwordHash).roles('OPERATOR').build())
    }

    static void validateCredentials(SecurityProperties properties, PasswordEncoder encoder) {
        if (!(properties.username ==~ '[A-Za-z0-9._-]{1,100}'))
            throw new IllegalArgumentException('Configure a valid operator username.')
        if (properties.passwordHash == null || !(properties.passwordHash ==~ '\\$2[aby]\\$(1[2-6])\\$[./A-Za-z0-9]{53}'))
            throw new IllegalArgumentException('Configure an operator BCrypt hash with cost 12 to 16 using scripts/setup.')
        for (String weak : ['password', 'changeme', 'change-me', 'admin', 'operator', 'test', 'fnord', 'change-me-before-use']) {
            if (encoder.matches(weak, properties.passwordHash)) throw new IllegalArgumentException('Placeholder operator credentials are not permitted.')
        }
    }

    @Bean SecurityFilterChain security(HttpSecurity http, SecurityProperties properties, ProblemWriter problems, AuditService audit) {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse()
        csrf.setCookieCustomizer { cookie -> cookie.path('/').sameSite('Strict').secure(properties.secureCookies) }
        http.csrf { config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(new SpaCsrfHandler()) }
        http.authorizeHttpRequests { rules -> rules
            .requestMatchers('/api/v1/session', '/api/v1/session/login', '/api/v1/system/health', '/actuator/health', '/actuator/health/**').permitAll()
            .anyRequest().authenticated() }
        http.exceptionHandling { errors -> errors
            .authenticationEntryPoint { request, response, failure -> problems.write(request,response,401,'AUTHENTICATION_REQUIRED','Sign in to continue.') }
            .accessDeniedHandler { request, response, failure -> problems.write(request,response,403,'ACCESS_DENIED','The session or CSRF token is invalid. Refresh and try again.') } }
        http.formLogin { login -> login.loginProcessingUrl('/api/v1/session/login')
            .successHandler { request, response, authentication ->
                try { audit.record(authentication.name, 'LOGIN_SUCCEEDED', (String)request.getAttribute('correlationId')) }
                catch (Exception ignored) {
                    request.getSession(false)?.invalidate()
                    SecurityContextHolder.clearContext()
                    problems.write(request,response,503,'AUDIT_UNAVAILABLE','Sign-in is unavailable. Try again later.')
                    return
                }
                response.status = 204
            }
            .failureHandler { request, response, exception ->
                audit.record('anonymous', 'LOGIN_FAILED', (String)request.getAttribute('correlationId'))
                problems.write(request,response,401,'INVALID_CREDENTIALS','The username or password is incorrect.')
            } }
        http.logout { logout -> logout.logoutUrl('/api/v1/session/logout')
            .deleteCookies('JSESSIONID', 'XSRF-TOKEN')
            .logoutSuccessHandler { request, response, authentication ->
                if (authentication != null) audit.record(authentication.name,'LOGOUT',(String)request.getAttribute('correlationId'))
                response.status = 204
            } }
        http.headers { headers -> headers.contentSecurityPolicy { csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'") } }
        http.addFilterBefore(new LoginRateLimiter(problems), UsernamePasswordAuthenticationFilter)
        http.build()
    }
}
