package ca.fnord.dedup.security

import groovy.transform.CompileStatic
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.web.csrf.*
import org.springframework.util.StringUtils
import java.util.function.Supplier

@CompileStatic
class SpaCsrfHandler implements CsrfTokenRequestHandler {
    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler()
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler()
    @Override void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrf) {
        xor.handle(request, response, csrf)
        csrf.get() // Materialize the separate readable cookie, including after session refresh.
    }
    @Override String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
        (StringUtils.hasText(request.getHeader(token.headerName)) ? plain : xor).resolveCsrfTokenValue(request, token)
    }
}
