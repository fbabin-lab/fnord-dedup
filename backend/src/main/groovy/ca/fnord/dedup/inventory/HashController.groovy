package ca.fnord.dedup.inventory

import groovy.transform.CompileStatic
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@CompileStatic
@RestController
@RequestMapping('/api/v1')
class HashController {
    final HashService hashes
    HashController(HashService hashes) { this.hashes=hashes }
    @PostMapping('/hash-jobs') @ResponseStatus(HttpStatus.ACCEPTED)
    Map create(@RequestBody Map<String,Object> body,@RequestHeader(value='Idempotency-Key',required=false) String key,Authentication auth,HttpServletRequest request) {
        hashes.create(body,key,auth.name,(String)request.getAttribute('correlationId'))
    }
    @GetMapping('/observations/{id}/hash-attempts')
    Map attempts(@PathVariable('id') UUID id,@RequestParam(value='cursor',required=false) String cursor,@RequestParam(value='limit',defaultValue='20') int limit) { hashes.attempts(id,cursor,limit) }
    @GetMapping('/scans/{id}/duplicate-groups')
    Map groups(@PathVariable('id') UUID id,@RequestParam(value='analysisId',required=false) UUID revision,@RequestParam(value='cursor',required=false) String cursor,@RequestParam(value='limit',defaultValue='50') int limit) { hashes.groups(id,revision,cursor,limit) }
    @GetMapping('/duplicate-groups/{id}')
    Map group(@PathVariable('id') UUID id,@RequestParam(value='cursor',required=false) String cursor,@RequestParam(value='limit',defaultValue='100') int limit) { hashes.group(id,cursor,limit) }
}
