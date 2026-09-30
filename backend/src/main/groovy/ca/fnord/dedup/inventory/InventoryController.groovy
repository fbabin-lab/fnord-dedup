package ca.fnord.dedup.inventory

import ca.fnord.dedup.security.ProblemWriter
import groovy.transform.CompileStatic
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.http.converter.HttpMessageNotReadableException

@CompileStatic
@RestController
@RequestMapping('/api/v1')
class InventoryController {
    private final InventoryService inventory
    InventoryController(InventoryService inventory) { this.inventory = inventory }
    @PostMapping('/scans')
    ResponseEntity<Map<String,Object>> create(@RequestBody Map<String,Object> body,
        @RequestHeader(value='Idempotency-Key',required=false) String key, Authentication auth, HttpServletRequest request) {
        ResponseEntity.accepted().body(inventory.create(body,key,auth.name,(String)request.getAttribute('correlationId')))
    }
    @GetMapping('/scans') Map scans(@RequestParam(value='cursor',required=false) String cursor, @RequestParam(value='limit',defaultValue='100') int limit) { inventory.scans(cursor,limit) }
    @GetMapping('/scans/{id}') Map scan(@PathVariable('id') UUID id) { inventory.scan(id) }
    @GetMapping('/jobs') Map jobs(@RequestParam(value='cursor',required=false) String cursor, @RequestParam(value='limit',defaultValue='100') int limit) { inventory.jobs(cursor,limit) }
    @GetMapping('/jobs/{id}') Map job(@PathVariable('id') UUID id) { inventory.job(id) }
    @PostMapping('/jobs/{id}/{action:pause|resume|cancel}') Map control(@PathVariable('id') UUID id,@PathVariable('action') String action,
        @RequestBody(required=false) Map<String,Object> body,Authentication auth,HttpServletRequest request) {
        Map<String,Object> options = body == null ? new LinkedHashMap<String,Object>() : body
        inventory.control(id,action,auth.name,(String)request.getAttribute('correlationId'),options)
    }
    @GetMapping('/jobs/{id}/errors') Map errors(@PathVariable('id') UUID id,@RequestParam(value='cursor',required=false) String cursor,@RequestParam(value='limit',defaultValue='100') int limit) { inventory.errors(id,cursor,limit) }
    @GetMapping('/scans/{scanId}/directories/{locationId}/children') Map children(@PathVariable('scanId') UUID scanId,@PathVariable('locationId') UUID locationId,@RequestParam(value='cursor',required=false) String cursor,@RequestParam(value='limit',defaultValue='100') int limit) { inventory.children(scanId,locationId,cursor,limit) }
    @GetMapping('/observations/{id}') Map observation(@PathVariable('id') UUID id) { inventory.observation(id) }
}

@CompileStatic
@RestControllerAdvice
class InventoryProblems {
    final ProblemWriter problems
    InventoryProblems(ProblemWriter problems) { this.problems = problems }
    @ExceptionHandler(JobProblem)
    void problem(JobProblem e,HttpServletRequest request,HttpServletResponse response) { problems.write(request,response,e.status,e.code,e.message) }
    @ExceptionHandler([MethodArgumentTypeMismatchException,HttpMessageNotReadableException])
    void invalid(Exception e,HttpServletRequest request,HttpServletResponse response) { problems.write(request,response,400,'INVALID_REQUEST','The request contains an invalid identifier, parameter or JSON body.') }
    @ExceptionHandler([org.springframework.dao.DataAccessException,org.springframework.transaction.TransactionException])
    void database(Exception e,HttpServletRequest request,HttpServletResponse response) { problems.write(request,response,503,'DATABASE_UNAVAILABLE','The database operation could not be confirmed. Retry creation with the same Idempotency-Key, or reload the stored job state.') }
}
