package ca.fnord.dedup.security

import groovy.transform.CompileStatic
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@CompileStatic
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
        String id = UUID.randomUUID().toString()
        request.setAttribute('correlationId', id)
        response.setHeader('X-Correlation-ID', id)
        MDC.put('correlationId', id)
        try { chain.doFilter(request, response) } finally { MDC.remove('correlationId') }
    }
}
