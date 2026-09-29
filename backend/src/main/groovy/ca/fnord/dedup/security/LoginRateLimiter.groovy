package ca.fnord.dedup.security

import groovy.transform.CompileStatic
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.http.HttpMethod
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher
import java.time.Clock

/** Global single-operator throttle avoids trusting client-controlled forwarded IP headers. */
@CompileStatic
class LoginRateLimiter extends OncePerRequestFilter {
    private final ProblemWriter problems
    private final Clock clock
    private final ArrayDeque<Long> attempts = new ArrayDeque<>()
    private final RequestMatcher loginPath = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, '/api/v1/session/login')
    LoginRateLimiter(ProblemWriter problems, Clock clock = Clock.systemUTC()) {
        this.problems = problems; this.clock = clock
    }
    synchronized boolean acquire() {
        long now = clock.millis()
        while (!attempts.isEmpty() && attempts.peekFirst() <= now - 60000L) attempts.removeFirst()
        if (attempts.size() >= 5) return false
        attempts.addLast(now)
        return true
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
        if (loginPath.matches(request) && !acquire()) {
            response.setHeader('Retry-After', '60')
            problems.write(request, response, 429, 'LOGIN_RATE_LIMIT', 'Too many login attempts. Try again in one minute.')
            return
        }
        chain.doFilter(request, response)
    }
}
