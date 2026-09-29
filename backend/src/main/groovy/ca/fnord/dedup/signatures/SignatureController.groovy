package ca.fnord.dedup.signatures

import ca.fnord.dedup.inventory.JobProblem
import groovy.transform.CompileStatic
import jakarta.servlet.http.*
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@CompileStatic
@RestController
@RequestMapping('/api/v1')
class SignatureController {
    final SignatureCatalog catalog
    final SignatureService signatures
    final SignatureExchange exchange
    final SignatureExports exports
    final SignatureLimits limits
    SignatureController(SignatureCatalog catalog,SignatureService signatures,SignatureExchange exchange,SignatureExports exports,SignatureLimits limits) { this.catalog=catalog; this.signatures=signatures; this.exchange=exchange; this.exports=exports; this.limits=limits }
    @GetMapping('/signatures') Map list(@RequestParam(value='query',defaultValue='') String query,@RequestParam(value='catalogRevision',required=false) String revision,@RequestParam(value='cursor',required=false) String cursor,@RequestParam(value='limit',defaultValue='50') int limit) { catalog.list(query,revision,cursor,limit) }
    @GetMapping('/signature-limits') Map limits() { [importBytes:limits.importBytes,importRows:limits.importRows,exportBytes:limits.exportBytes] }
    @GetMapping('/signatures/{id}') Map get(@PathVariable('id') UUID id,@RequestParam(value='revision',required=false) Long revision) { catalog.get(id,revision) }
    @PostMapping('/signatures') Map create(@RequestBody Map body,Authentication auth,HttpServletRequest request) { catalog.put(null,body,auth.name,correlation(request)) }
    @PatchMapping('/signatures/{id}') Map edit(@PathVariable('id') UUID id,@RequestBody Map body,Authentication auth,HttpServletRequest request) { catalog.put(id,body,auth.name,correlation(request)) }
    @PostMapping('/signature-imports') Map stage(@RequestParam('format') String format,@RequestParam(value='policy',defaultValue='REJECT_EXISTING_ID') String policy,Authentication auth,HttpServletRequest request) { exchange.stage(exchange.bounded(request.inputStream),format,policy,auth.name,correlation(request)) }
    @GetMapping('/signature-imports/{id}') Map dryRun(@PathVariable('id') UUID id,Authentication auth,@RequestParam(value='cursor',required=false) String cursor,@RequestParam(value='limit',defaultValue='100') int limit) { exchange.get(id,auth.name,cursor,limit) }
    @PostMapping('/signature-imports/{id}/apply') Map apply(@PathVariable('id') UUID id,@RequestBody Map body,@RequestHeader(value='Idempotency-Key',required=false) String key,Authentication auth,HttpServletRequest request) { exchange.apply(id,body,key,auth.name,correlation(request)) }
    @PostMapping('/scans/{id}/signature-check-preview') Map preview(@PathVariable('id') UUID id,Authentication auth) { signatures.preview(id,auth.name) }
    @PostMapping('/signature-check-jobs') Map check(@RequestBody Map body,@RequestHeader(value='Idempotency-Key',required=false) String key,Authentication auth,HttpServletRequest request) { signatures.check(body,key,auth.name,correlation(request)) }
    @GetMapping('/observations/{id}/signatures') Map observation(@PathVariable('id') UUID id,@RequestParam(value='runId',required=false) UUID runId,@RequestParam(value='cursor',required=false) String cursor,@RequestParam(value='limit',defaultValue='50') int limit) { signatures.observation(id,runId,cursor,limit) }
    @GetMapping('/scans/{id}/signature-findings') Map findings(@PathVariable('id') UUID id,@RequestParam(value='cursor',required=false) String cursor,@RequestParam(value='limit',defaultValue='100') int limit) { signatures.findings(id,cursor,limit) }
    @GetMapping('/scans/{id}/signature-runs') Map runs(@PathVariable('id') UUID id,@RequestParam(value='cursor',required=false) String cursor,@RequestParam(value='limit',defaultValue='100') int limit) { signatures.runs(id,cursor,limit) }
    @PostMapping('/signature-exports') Map export(@RequestBody Map body,@RequestHeader(value='Idempotency-Key',required=false) String key,Authentication auth,HttpServletRequest request) { exports.create(body,key,auth.name,correlation(request)) }
    @GetMapping('/signature-exports/{id}') Map exportStatus(@PathVariable('id') UUID id,Authentication auth) { exports.get(id,auth.name) }
    @PostMapping('/signature-exports/{id}/{action}') Map controlExport(@PathVariable('id') UUID id,@PathVariable('action') String action,Authentication auth,HttpServletRequest request) { exports.control(id,action,auth.name,correlation(request)) }
    @GetMapping('/signature-exports/{id}/download') void download(@PathVariable('id') UUID id,Authentication auth,HttpServletResponse response) {
        Map row=exports.get(id,auth.name)
        if (row.state!='READY') throw new JobProblem(409,'EXPORT_NOT_READY','Only complete published artifacts can be downloaded.')
        response.contentType=row.format=='JSON' ? 'application/json' : 'text/csv;charset=UTF-8'
        response.setHeader('Content-Disposition','attachment; filename="signature-catalog-'+id+(row.format=='JSON' ? '.json' : '.csv')+'"')
        response.setHeader('X-Content-Type-Options','nosniff')
        response.setContentLengthLong(Long.parseLong(row.byteCount.toString()))
        exports.download(id,auth.name,response.outputStream)
    }
    private static String correlation(HttpServletRequest request) { (String)request.getAttribute('correlationId') }
}
