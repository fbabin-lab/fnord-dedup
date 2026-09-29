package ca.fnord.dedup.inventory

import ca.fnord.dedup.explorer.SelectionService
import ca.fnord.dedup.explorer.Values
import groovy.transform.CompileStatic
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@CompileStatic
@Service
class HashService {
    final InventoryStore store
    final InventoryService inventory
    final SelectionService selections
    HashService(InventoryStore store, InventoryService inventory, SelectionService selections) { this.store=store; this.inventory=inventory; this.selections=selections }
    Map create(Map<String,Object> body, String key, String actor, String correlation) {
        if (!Set.of('scanId','observationIds','selectionId','forceRehash').containsAll(body.keySet()) || !(body.getOrDefault('forceRehash',false) instanceof Boolean))
            throw new JobProblem(422,'INVALID_HASH_REQUEST','Use observation IDs and a boolean forceRehash option.')
        UUID scanId
        UUID selectionId=body.containsKey('selectionId') ? Values.id(body.selectionId) : null
        if (body.containsKey('observationIds') == (selectionId!=null)) throw new JobProblem(422,'INVALID_SELECTION','Provide observationIds or one frozen selectionId.')
        List<UUID> ids = new ArrayList<>()
        try {
            scanId=UUID.fromString((String)body.scanId)
            if (selectionId==null && (!(body.observationIds instanceof List) || ((List)body.observationIds).isEmpty() || ((List)body.observationIds).size()>500)) throw new IllegalArgumentException()
            for (Object id : (List)(body.observationIds ?: [])) ids.add(UUID.fromString((String)id))
            if (new HashSet<UUID>(ids).size()!=ids.size()) throw new IllegalArgumentException()
        } catch (IllegalArgumentException | ClassCastException | NullPointerException ignored) { throw new JobProblem(422,'INVALID_SELECTION','Select 1 to 500 distinct observation UUIDs in one scan.') }
        ids.sort { UUID a, UUID b -> a.compareTo(b) }
        boolean force=body.forceRehash == Boolean.TRUE
        String requestKey=key == null ? UUID.randomUUID().toString() : key
        if (!(requestKey ==~ '[!-~]{1,128}')) throw new JobProblem(422,'INVALID_IDEMPOTENCY_KEY','Use 1 to 128 printable ASCII characters.')
        Map payload=selectionId==null ? [scanId:scanId,observationIds:ids,forceRehash:force] : [scanId:scanId,selectionId:selectionId,forceRehash:force]
        String payloadHash=HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(store.json(payload).getBytes(StandardCharsets.UTF_8)))
        store.tx.execute { status ->
            store.one('SELECT id FROM job_admission WHERE id=1 FOR UPDATE')
            Map previous=store.one("SELECT payload_hash,response::text AS response FROM idempotency_record WHERE actor=? AND endpoint='/hash-jobs' AND request_key=?",actor,requestKey)
            if (previous!=null) {
                if (previous.payload_hash!=payloadHash) throw new JobProblem(409,'IDEMPOTENCY_CONFLICT','This key was used for a different request.')
                return store.parse((String)previous.response)
            }
            if (store.jdbc.queryForObject("SELECT count(*) FROM job WHERE state NOT IN ('COMPLETED','COMPLETED_WITH_ERRORS','CANCELLED','FAILED')",Long)>=10L ||
                store.jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE actor=? AND endpoint IN ('/scans','/hash-jobs') AND created_at>clock_timestamp()-interval '1 minute'",Long,actor)>=5L)
                throw new JobProblem(429,'JOB_CAPACITY','The job queue or creation rate limit has been reached. Retry later.')
            if (selectionId!=null) store.one('SELECT id FROM annotation_clock WHERE id=1 FOR SHARE')
            Map scan=store.one('SELECT * FROM scan WHERE id=? FOR UPDATE',scanId)
            if (scan==null) throw new JobProblem(404,'SCAN_NOT_FOUND','The scan does not exist.')
            if (scan.inventory_frozen_at==null) throw new JobProblem(409,'INVENTORY_NOT_FROZEN','Wait for inventory to finish before requesting extra hashes.')
            if (selectionId!=null) ids.addAll(selections.hashIds(selectionId,actor,scanId))
            List<Map> entries=new ArrayList<>()
            for (UUID id : ids) {
                Map entry=store.one('SELECT e.* FROM scan_entry e WHERE e.id=? AND e.scan_id=? AND '+HashPipeline.ELIGIBLE,id,scanId)
                if (entry==null) throw new JobProblem(422,'OBSERVATION_INELIGIBLE','Every selected observation must be a stable regular file with usable metadata in this scan.')
                entries.add(entry)
            }
            UUID jobId=UUID.randomUUID()
            store.jdbc.update("INSERT INTO job(id,scan_id,type,phase,state) VALUES (?,?,'HASH','HASHING','QUEUED')",jobId,scanId)
            HashPipeline pipeline=new HashPipeline(store,inventory.sources)
            for (Map entry : entries) pipeline.addHash(jobId,entry,force,force ? ['MANUAL','FORCED_RECHECK'] : ['MANUAL'])
            store.jdbc.update('UPDATE scan SET query_revision=query_revision+1 WHERE id=?',scanId)
            store.event(jobId,'QUEUED',actor,[forceRehash:force,observationIds:ids])
            store.jdbc.update('INSERT INTO audit_event(id,actor,action,correlation_id,details) VALUES (?,?,?,?,?::jsonb)',UUID.randomUUID(),actor,'HASH_REQUESTED',UUID.fromString(correlation),store.json([scanId:scanId,jobId:jobId,observationIds:ids,forceRehash:force]))
            Map response=[scanId:scanId.toString(),jobId:jobId.toString()]
            store.jdbc.update("INSERT INTO idempotency_record(actor,endpoint,request_key,payload_hash,response) VALUES (?,'/hash-jobs',?,?,?::jsonb)",actor,requestKey,payloadHash,store.json(response))
            response
        }
    }
    static Map evidence(InventoryStore store, UUID entryId) {
        Map accepted=store.one('SELECT a.*,h.active,h.invalidation_code FROM accepted_hash h JOIN hash_attempt a ON a.id=h.attempt_id WHERE h.entry_id=?',entryId)
        Map latest=store.one('SELECT * FROM hash_attempt WHERE entry_id=? ORDER BY sequence DESC LIMIT 1',entryId)
        Map state=store.one('''SELECT e.entry_type,e.discovery_status,s.candidates_frozen_at,s.evidence_block_code,s.options->>'inventoryOnly' AS inventory_only,
            EXISTS(SELECT 1 FROM observation_validation v WHERE v.entry_id=e.id) AS unstable,
            EXISTS(SELECT 1 FROM candidate_size cs WHERE cs.scan_id=e.scan_id AND cs.size_bytes=e.size_bytes) AS candidate,
            EXISTS(SELECT 1 FROM work_item w JOIN job j ON j.id=w.job_id WHERE w.entry_id=e.id AND w.state IN ('READY','LEASED')
                AND j.state NOT IN ('COMPLETED','COMPLETED_WITH_ERRORS','CANCELLED','FAILED')) AS pending
            FROM scan_entry e JOIN scan s ON s.id=e.scan_id WHERE e.id=?''',entryId)
        String result = state.entry_type!='REGULAR' || state.discovery_status!='OBSERVED' ? 'INELIGIBLE' : state.evidence_block_code!=null || state.unstable==Boolean.TRUE || accepted?.active==Boolean.FALSE ? 'STALE' :
            accepted?.active==Boolean.TRUE ? 'ACCEPTED' : state.pending==Boolean.TRUE ? 'PENDING' : latest!=null ? 'FAILED' :
            state.candidates_frozen_at!=null && state.inventory_only!='true' && state.candidate!=Boolean.TRUE ? 'NOT_REQUESTED_UNIQUE_SIZE' : 'NOT_REQUESTED'
        [status:result,pending:state.pending,accepted:accepted==null ? null : attempt(store,accepted),latestAttempt:latest==null ? null : attempt(store,latest),invalidationCode:state.evidence_block_code ?: accepted?.invalidation_code]
    }
    private static Map attempt(InventoryStore store, Map row) {
        [id:row.id,jobId:row.job_id,algorithm:row.algorithm,digest:row.digest==null ? null : HexFormat.of().formatHex((byte[])row.digest),
         bytesRead:row.bytes_read.toString(),startedAt:InventoryService.time(row.started_at),completedAt:InventoryService.time(row.completed_at),
         reasons:store.mapper.readValue(row.reasons.toString(),List),outcome:row.outcome,errorCode:row.error_code,errorDetail:row.error_detail,
         preFingerprint:row.pre_fingerprint==null ? null : store.parse(row.pre_fingerprint.toString()),postFingerprint:row.post_fingerprint==null ? null : store.parse(row.post_fingerprint.toString())]
    }
    Map attempts(UUID id,String cursor,int limit) {
        inventory.observation(id)
        String scope='hash-attempts:'+id
        Map page=inventory.page(scope,cursor,limit,'SELECT coalesce(max(sequence),0) FROM hash_attempt WHERE entry_id=?',id)
        List<Map<String,Object>> rows=store.jdbc.queryForList('SELECT * FROM hash_attempt WHERE entry_id=? AND sequence>? AND sequence<=? ORDER BY sequence LIMIT ?',id,page.after,page.cutoff,limit+1)
        boolean more=rows.size()>limit
        if (more) rows.removeLast()
        [items:rows.collect { Map row -> attempt(store,row) },nextCursor:more ? inventory.cursorFor(scope,page.cutoff,rows.getLast().sequence) : null]
    }
    Map groups(UUID scanId, UUID requestedRevision, String cursor, int limit) {
        Map scan=store.one('SELECT * FROM scan WHERE id=?',scanId)
        if (scan==null) throw new JobProblem(404,'SCAN_NOT_FOUND','The scan does not exist.')
        UUID revision=requestedRevision ?: (UUID)scan.current_analysis_id
        if (revision==null) return [analysisId:null,evidenceRevision:scan.evidence_revision.toString(),status:'NOT_AVAILABLE',items:[],nextCursor:null]
        Map analysis=store.one("SELECT * FROM analysis_revision WHERE id=? AND scan_id=? AND state='PUBLISHED'",revision,scanId)
        if (analysis==null) throw new JobProblem(404,'ANALYSIS_NOT_FOUND','This published analysis revision does not exist in the scan.')
        String scope='groups:'+scanId+':'+revision
        Map page=inventory.page(scope,cursor,limit,'SELECT coalesce(max(sequence),0) FROM duplicate_group WHERE analysis_id=?',revision)
        List<Map<String,Object>> rows=store.jdbc.queryForList('SELECT * FROM duplicate_group WHERE analysis_id=? AND sequence>? AND sequence<=? ORDER BY sequence LIMIT ?',revision,page.after,page.cutoff,limit+1)
        boolean more=rows.size()>limit
        if (more) rows.removeLast()
        [analysisId:revision,evidenceRevision:analysis.evidence_revision.toString(),status:analysis.evidence_revision==scan.evidence_revision ? 'CURRENT' : 'NEEDS_REBUILD',
         items:rows.collect { Map row -> groupRow(row) },nextCursor:more ? inventory.cursorFor(scope,page.cutoff,rows.getLast().sequence) : null]
    }
    private Map groupRow(Map row) {
        boolean stale=store.one('''SELECT i.entry_id FROM analysis_input i LEFT JOIN accepted_hash h ON h.entry_id=i.entry_id AND h.algorithm=i.algorithm
            WHERE i.group_id=? AND (EXISTS(SELECT 1 FROM scan s WHERE s.id=i.scan_id AND s.evidence_block_code IS NOT NULL) OR h.active IS DISTINCT FROM true OR h.attempt_id IS DISTINCT FROM i.attempt_id
                OR EXISTS(SELECT 1 FROM observation_validation v WHERE v.entry_id=i.entry_id)) LIMIT 1''',row.id)!=null
        List<Map<String,Object>> roots=store.jdbc.queryForList('''SELECT DISTINCT e.source_id FROM analysis_input i JOIN scan_entry e ON e.id=i.entry_id WHERE i.group_id=? ORDER BY e.source_id''',row.id)
        BigInteger size=new BigInteger(row.size_bytes.toString()), paths=new BigInteger(row.path_count.toString())
        BigInteger objects=row.object_count==null ? null : new BigInteger(row.object_count.toString())
        [id:row.id,analysisId:row.analysis_id,scanId:row.scan_id,sizeBytes:size.toString(),algorithm:row.algorithm,digest:HexFormat.of().formatHex((byte[])row.digest),
         pathCount:paths.toString(),objectCount:objects?.toString(),identityStatus:row.identity_status,evidenceLevel:stale ? 'STALE' : 'HASH_IDENTICAL',sourceIds:roots.collect { Map root -> root.source_id },
         pathLogicalBytes:(size*paths).toString(),independentObjectLogicalBytes:objects==null ? null : (size*objects).toString(),
         maximumDuplicateCopyLogicalBytes:objects==null ? null : (size*(objects-BigInteger.ONE).max(BigInteger.ZERO)).toString(),physicalSavingsBytes:null]
    }
    Map group(UUID id,String cursor,int limit) {
        Map row=store.one("SELECT g.* FROM duplicate_group g JOIN analysis_revision r ON r.id=g.analysis_id WHERE g.id=? AND r.state='PUBLISHED'",id)
        if (row==null) throw new JobProblem(404,'GROUP_NOT_FOUND','The published duplicate group does not exist.')
        String scope='members:'+id
        Map page=inventory.page(scope,cursor,limit,'SELECT coalesce(max(entry_sequence),0) FROM analysis_input WHERE group_id=?',id)
        List<Map<String,Object>> rows=store.jdbc.queryForList('''SELECT i.entry_id,i.entry_sequence,a.* FROM analysis_input i JOIN hash_attempt a ON a.id=i.attempt_id
            WHERE i.group_id=? AND i.entry_sequence>? AND i.entry_sequence<=? ORDER BY i.entry_sequence LIMIT ?''',id,page.after,page.cutoff,limit+1)
        boolean more=rows.size()>limit
        if (more) rows.removeLast()
        [group:groupRow(row),members:rows.collect { Map member -> [observation:inventory.observation((UUID)member.entry_id),hash:attempt(store,member)] },
         nextCursor:more ? inventory.cursorFor(scope,page.cutoff,rows.getLast().entry_sequence) : null]
    }
}
