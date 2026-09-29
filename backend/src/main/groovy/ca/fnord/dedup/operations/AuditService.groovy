package ca.fnord.dedup.operations

import groovy.transform.CompileStatic
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@CompileStatic
@Service
class AuditService {
    private final JdbcTemplate jdbc
    AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc }
    void record(String actor, String action, String correlationId) {
        jdbc.update('INSERT INTO audit_event (id, actor, action, correlation_id) VALUES (?, ?, ?, ?)',
            UUID.randomUUID(), actor, action, UUID.fromString(correlationId))
    }
}
