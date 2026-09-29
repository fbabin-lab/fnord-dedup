package ca.fnord.dedup.explorer

import groovy.transform.CompileStatic
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@CompileStatic
@RestController
@RequestMapping('/api/v1')
class ExplorerController {
    final SearchService search
    final AnnotationService annotations
    final SelectionService selections
    ExplorerController(SearchService search,AnnotationService annotations,SelectionService selections) { this.search=search; this.annotations=annotations; this.selections=selections }
    @PostMapping('/scans/{id}/files/search') Map search(@PathVariable('id') UUID id,@RequestBody Map body) { search.search(id,body) }
    @GetMapping('/scans/{scanId}/directories/{locationId}') Map directory(@PathVariable('scanId') UUID scanId,@PathVariable('locationId') UUID locationId) { search.directory(scanId,locationId) }
    @GetMapping('/locations/{id}/annotation') Map annotation(@PathVariable('id') UUID id,@RequestParam(value='observationId',required=false) UUID observationId) { annotations.get(id,observationId) }
    @PutMapping('/locations/{id}/annotation') Map save(@PathVariable('id') UUID id,@RequestBody Map body,Authentication auth,HttpServletRequest request) { annotations.save(id,body,auth.name,correlation(request)) }
    @GetMapping('/locations/{id}/history') Map history(@PathVariable('id') UUID id,@RequestParam(value='cursor',required=false) String cursor,@RequestParam(value='limit',defaultValue='100') int limit) { annotations.history(id,cursor,limit) }
    @GetMapping('/tags') Map tags(@RequestParam(value='query',defaultValue='') String query,@RequestParam(value='cursor',required=false) String cursor,@RequestParam(value='limit',defaultValue='100') int limit) { annotations.tags(query,cursor,limit) }
    @PostMapping('/tags') Map createTag(@RequestBody Map body,Authentication auth,HttpServletRequest request) { annotations.putTag(null,body,auth.name,correlation(request)) }
    @PatchMapping('/tags/{id}') Map renameTag(@PathVariable('id') UUID id,@RequestBody Map body,Authentication auth,HttpServletRequest request) { annotations.putTag(id,body,auth.name,correlation(request)) }
    @PostMapping('/scans/{id}/selections') Map freeze(@PathVariable('id') UUID id,@RequestBody Map body,Authentication auth,HttpServletRequest request) { selections.freeze(id,body,auth.name,correlation(request)) }
    @GetMapping('/selections/{id}') Map selection(@PathVariable('id') UUID id,Authentication auth,@RequestParam(value='cursor',required=false) String cursor,@RequestParam(value='limit',defaultValue='100') int limit) { selections.get(id,auth.name,cursor,limit) }
    @PostMapping('/annotation-batches') Map batch(@RequestBody Map body,@RequestHeader(value='Idempotency-Key',required=false) String key,Authentication auth,HttpServletRequest request) { selections.apply(body,key,auth.name,correlation(request)) }
    private static String correlation(HttpServletRequest request) { (String)request.getAttribute('correlationId') }
}
