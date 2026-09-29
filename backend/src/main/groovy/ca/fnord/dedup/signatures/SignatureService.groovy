package ca.fnord.dedup.signatures

import ca.fnord.dedup.explorer.*
import ca.fnord.dedup.inventory.*
import groovy.transform.CompileStatic
import org.springframework.stereotype.Service

@CompileStatic
@Service
class SignatureService {
    final InventoryStore store
    final InventoryService inventory
    final SignatureCatalog catalog
    final AnnotationService annotations
    SignatureService(InventoryStore store,InventoryService inventory,SignatureCatalog catalog,AnnotationService annotations) { this.store=store; this.inventory=inventory; this.catalog=catalog; this.annotations=annotations }
    Map preview(UUID scanId,String actor) {
        store.tx.execute { tx ->
            Map scan=store.one('SELECT * FROM scan WHERE id=? FOR SHARE',scanId)
            if (scan==null) throw new JobProblem(404,'SCAN_NOT_FOUND','The scan does not exist.')
            if (scan.inventory_frozen_at==null) throw new JobProblem(409,'INVENTORY_NOT_FROZEN','Wait for inventory to finish before checking signatures.')
            long revision=catalog.revision()
            long cutoff=store.jdbc.queryForObject('SELECT coalesce(max(sequence),0) FROM scan_entry WHERE scan_id=?',Long,scanId)
            Map totals=store.one('''SELECT count(*) AS files,coalesce(sum(e.size_bytes::numeric),0) AS bytes FROM scan_entry e
                WHERE e.scan_id=? AND e.sequence<=? AND '''+HashPipeline.ELIGIBLE+'''
                AND EXISTS(SELECT 1 FROM signatures_at(?) r WHERE r.enabled AND r.size_bytes=e.size_bytes)
                AND NOT EXISTS(SELECT 1 FROM accepted_hash h WHERE h.entry_id=e.id)''',scanId,cutoff,revision)
            UUID id=UUID.randomUUID()
            store.jdbc.update('''INSERT INTO signature_preview(id,actor,scan_id,catalog_revision,evidence_revision,entry_cutoff,candidate_files,candidate_bytes)
                VALUES (?,?,?,?,?,?,?,?)''',id,actor,scanId,revision,scan.evidence_revision,cutoff,totals.files,totals.bytes)
            [id:id,scanId:scanId,catalogRevision:Long.toString(revision),candidateFiles:totals.files.toString(),candidateBytes:totals.bytes.toString(),
             expiresAt:InventoryService.time(store.one('SELECT expires_at FROM signature_preview WHERE id=?',id).expires_at)]
        }
    }
    Map check(Map body,String key,String actor,String correlation) {
        Values.fields(body,['previewId','allowBodyReads'])
        UUID previewId=Values.id(body.previewId)
        if (body.allowBodyReads!=Boolean.TRUE) Values.invalid('Explicit authorization to read signature-size candidates is required.')
        String requestKey=key ?: UUID.randomUUID().toString()
        if (!(requestKey ==~ '[!-~]{1,128}')) Values.invalid('Use a printable ASCII Idempotency-Key of at most 128 characters.')
        String hash=Values.hash(store.json([previewId:previewId,allowBodyReads:true]))
        store.tx.execute { tx ->
            store.one('SELECT id FROM job_admission WHERE id=1 FOR UPDATE')
            Map previous=store.one("SELECT * FROM idempotency_record WHERE actor=? AND endpoint='/signature-check-jobs' AND request_key=?",actor,requestKey)
            if (previous!=null) {
                if (previous.payload_hash!=hash) throw new JobProblem(409,'IDEMPOTENCY_CONFLICT','This key was used for another check request.')
                return store.parse(previous.response.toString())
            }
            if (store.jdbc.queryForObject("SELECT count(*) FROM job WHERE state NOT IN ('COMPLETED','COMPLETED_WITH_ERRORS','CANCELLED','FAILED')",Long)>=10L ||
                store.jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE actor=? AND endpoint IN ('/scans','/hash-jobs','/signature-check-jobs') AND created_at>clock_timestamp()-interval '1 minute'",Long,actor)>=5L)
                throw new JobProblem(429,'JOB_CAPACITY','The job queue or creation rate limit has been reached.')
            Map p=store.one('SELECT *,expires_at>clock_timestamp() AS valid FROM signature_preview WHERE id=? AND actor=? FOR UPDATE',previewId,actor)
            if (p==null) throw new JobProblem(404,'PREVIEW_NOT_FOUND','This signature-check preview does not exist.')
            if (p.job_id!=null) return [scanId:p.scan_id.toString(),jobId:p.job_id.toString()]
            Map scan=store.one('SELECT * FROM scan WHERE id=? FOR UPDATE',p.scan_id)
            if (p.valid!=Boolean.TRUE || p.evidence_revision!=scan.evidence_revision || ((Number)p.catalog_revision).longValue()!=catalog.revision())
                throw new JobProblem(409,'PREVIEW_STALE','Evidence or catalog changed, or this preview expired. Preview the read estimate again.')
            UUID job=UUID.randomUUID()
            store.jdbc.update("INSERT INTO job(id,scan_id,type,phase,state,signature_catalog_revision) VALUES (?,?,'SIGNATURE_CHECK','CANDIDATE_SELECTION','QUEUED',?)",job,p.scan_id,p.catalog_revision)
            UUID root=(UUID)store.one('SELECT root_location_id FROM scan_source WHERE scan_id=? ORDER BY source_id LIMIT 1',p.scan_id).root_location_id
            store.jdbc.update("INSERT INTO work_item(id,job_id,location_id,kind,payload) VALUES (?,?,?,'SIGNATURE_SELECT',?::jsonb)",UUID.randomUUID(),job,root,store.json([catalog:p.catalog_revision.toString(),cutoff:p.entry_cutoff.toString(),after:'0']))
            store.jdbc.update('UPDATE job SET pending_work=1 WHERE id=?',job)
            store.jdbc.update('UPDATE signature_preview SET job_id=? WHERE id=?',job,previewId)
            store.event(job,'QUEUED',actor,[previewId:previewId,catalogRevision:p.catalog_revision.toString(),allowBodyReads:true,candidateFiles:p.candidate_files.toString(),candidateBytes:p.candidate_bytes.toString()])
            annotations.audit(actor,'SIGNATURE_CHECK_AUTHORIZED',correlation,[previewId:previewId,jobId:job,scanId:p.scan_id,catalogRevision:p.catalog_revision.toString(),allowBodyReads:true])
            Map response=[scanId:p.scan_id.toString(),jobId:job.toString()]
            store.jdbc.update("INSERT INTO idempotency_record(actor,endpoint,request_key,payload_hash,response) VALUES (?,'/signature-check-jobs',?,?,?::jsonb)",actor,requestKey,hash,store.json(response))
            response
        }
    }
    static Map coverage(Map row) {
        [runId:row.run_id,catalogRevision:row.catalog_revision?.toString(),catalogCurrent:row.catalog_current==Boolean.TRUE,
         matchStatus:row.match_status,checkStatus:row.check_status,checkedAt:InventoryService.time(row.checked_at),attemptId:row.attempt_id]
    }
    Map observation(UUID id,UUID runId,String cursor,int limit) {
        store.tx.execute { tx -> observationLocked(id,runId,cursor,limit) }
    }
    private Map observationLocked(UUID id,UUID runId,String cursor,int limit) {
        Map observed=store.one('SELECT e.*,s.current_signature_run_id,s.evidence_revision FROM scan_entry e JOIN scan s ON s.id=e.scan_id WHERE e.id=? FOR SHARE OF s',id)
        if (observed==null) throw new JobProblem(404,'OBSERVATION_NOT_FOUND','This observation does not exist.')
        Map row
        if (runId==null || runId==observed.current_signature_run_id) {
            row=store.one('SELECT * FROM signature_coverage WHERE entry_id=?',id)
            runId=(UUID)row.run_id
        } else {
            row=store.one('''SELECT c.*,r.catalog_revision,r.catalog_revision=(SELECT revision FROM signature_clock WHERE id=1) AS catalog_current
                FROM signature_check c JOIN signature_run r ON r.id=c.run_id AND r.state='PUBLISHED' WHERE c.run_id=? AND c.entry_id=?''',runId,id)
            if (row==null) throw new JobProblem(404,'FINDINGS_NOT_FOUND','This published signature check does not include the observation.')
            // Historical results retain their original statuses, explicitly separated from current evidence.
        }
        Map result=coverage(row)+[observationId:id,current:runId!=null && runId==observed.current_signature_run_id,items:[],nextCursor:null]
        if (runId==null) return result
        String scope='matches:'+id+':'+runId
        Map page=inventory.page(scope,cursor,limit,'SELECT coalesce(max(sequence),0) FROM signature')
        List<Map<String,Object>> rows=store.jdbc.queryForList('''SELECT r.*,s.created_at,s.sequence,m.matched_at FROM signature_match m
            JOIN signature_revision r ON r.signature_id=m.signature_id AND r.revision=m.signature_revision JOIN signature s ON s.id=r.signature_id
            WHERE m.run_id=? AND m.entry_id=? AND s.sequence>? AND s.sequence<=? ORDER BY s.sequence LIMIT ?''',runId,id,page.after,page.cutoff,limit+1)
        boolean more=rows.size()>limit; if (more) rows.removeLast()
        result.put('items',rows.collect { Map finding -> [signature:catalog.dto(finding),matchedAt:InventoryService.time(finding.matched_at),active:result.current==Boolean.TRUE && row.match_status=='MATCHED'] })
        result.put('nextCursor',more ? inventory.cursorFor(scope,page.cutoff,rows.getLast().sequence) : null)
        result
    }
    Map findings(UUID scanId,String cursor,int limit) {
        inventory.scan(scanId)
        String scope='coverage:'+scanId+':'+store.one('SELECT current_signature_run_id FROM scan WHERE id=?',scanId).current_signature_run_id
        Map page=inventory.page(scope,cursor,limit,'SELECT coalesce(max(sequence),0) FROM scan_entry WHERE scan_id=?',scanId)
        List<Map<String,Object>> rows=store.jdbc.queryForList('''SELECT e.id,e.sequence,l.display_path,c.* FROM scan_entry e JOIN file_location l ON l.id=e.location_id
            JOIN signature_coverage c ON c.entry_id=e.id WHERE e.scan_id=? AND e.sequence>? AND e.sequence<=? ORDER BY e.sequence LIMIT ?''',scanId,page.after,page.cutoff,limit+1)
        boolean more=rows.size()>limit; if (more) rows.removeLast()
        [items:rows.collect { Map row -> coverage(row)+[observationId:row.id,displayPath:row.display_path] },nextCursor:more ? inventory.cursorFor(scope,page.cutoff,rows.getLast().sequence) : null]
    }
    Map runs(UUID scanId,String cursor,int limit) {
        Map scan=inventory.scan(scanId)
        String scope='signature-runs:'+scanId
        Map page=inventory.page(scope,cursor,limit,"SELECT coalesce(max(sequence),0) FROM signature_run WHERE scan_id=? AND state='PUBLISHED'",scanId)
        List<Map<String,Object>> rows=store.jdbc.queryForList("SELECT * FROM signature_run WHERE scan_id=? AND state='PUBLISHED' AND sequence>? AND sequence<=? ORDER BY sequence LIMIT ?",scanId,page.after,page.cutoff,limit+1)
        boolean more=rows.size()>limit; if (more) rows.removeLast()
        [items:rows.collect { Map row -> [id:row.id,jobId:row.job_id,catalogRevision:row.catalog_revision.toString(),evidenceRevision:row.evidence_revision.toString(),publishedAt:InventoryService.time(row.published_at),current:row.id==scan.signatureRunId] },nextCursor:more ? inventory.cursorFor(scope,page.cutoff,rows.getLast().sequence) : null]
    }
}
