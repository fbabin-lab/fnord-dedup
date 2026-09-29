package ca.fnord.dedup.security

import groovy.transform.CompileStatic
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@CompileStatic
@Component
class ProblemWriter {
    private final ObjectMapper mapper
    ProblemWriter(ObjectMapper mapper) { this.mapper = mapper }
    void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String detail) {
        response.status = status
        response.contentType = 'application/problem+json'
        mapper.writeValue(response.outputStream, [type:'about:blank', title:code, status:status,
            code:code, detail:detail, correlationId:request.getAttribute('correlationId'), retriable:status == 429 || status == 503])
    }
}
