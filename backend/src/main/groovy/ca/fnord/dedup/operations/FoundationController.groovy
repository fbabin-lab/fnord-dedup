package ca.fnord.dedup.operations

import ca.fnord.dedup.roots.SourceRegistry
import groovy.transform.CompileStatic
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@CompileStatic
@RestController
class FoundationController {
    private final SourceRegistry sources
    private final JdbcTemplate jdbc
    FoundationController(SourceRegistry sources, JdbcTemplate jdbc) { this.sources = sources; this.jdbc = jdbc }

    @GetMapping('/api/v1/session')
    Map<String, Object> session(Authentication authentication) {
        boolean authenticated = authentication != null && authentication.authenticated && !(authentication instanceof AnonymousAuthenticationToken)
        [authenticated:authenticated, username:authenticated ? authentication.name : null] as Map<String,Object>
    }
    @GetMapping('/api/v1/sources')
    Map<String, Object> sources() { [configurationRevision:sources.revision, sources:sources.list()] as Map<String,Object> }

    @GetMapping('/api/v1/system/info')
    Map<String,Object> info() {
        [name:'Fnord Dedup', version:'0.5.0-M4', milestone:'M4', platform:'Linux/amd64',
         sourcePolicy:'READ_ONLY', scanAvailable:true, inventoryOnly:false, configurationRevision:sources.revision] as Map<String,Object>
    }
    @GetMapping('/api/v1/system/health')
    ResponseEntity<Map<String,String>> health() {
        try {
            jdbc.queryForObject('SELECT 1', Integer)
            return ResponseEntity.ok([status:'UP'] as Map<String,String>)
        } catch (Exception ignored) { return ResponseEntity.status(503).body([status:'DOWN'] as Map<String,String>) }
    }
}
