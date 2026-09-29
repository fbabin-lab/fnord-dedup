package ca.fnord.dedup.signatures

import ca.fnord.dedup.inventory.*
import groovy.transform.CompileStatic

/** The existing fenced scheduler runs bounded database-only matching batches. */
@CompileStatic
class SignaturePipeline {
    final InventoryStore store
    SignaturePipeline(InventoryStore store) { this.store=store }
    static long current(InventoryStore store) { ((Number)store.one('SELECT revision FROM signature_clock WHERE id=1').revision).longValue() }
    static void stage(InventoryStore store,UUID job,UUID scan) {
        Map j=store.job(job)
        long catalog=j.signature_catalog_revision==null ? current(store) : ((Number)j.signature_catalog_revision).longValue()
        UUID location=(UUID)store.one('SELECT root_location_id FROM scan_source WHERE scan_id=? ORDER BY source_id LIMIT 1',scan).root_location_id
        store.jdbc.update("INSERT INTO work_item(id,job_id,location_id,kind,payload) VALUES (?,?,?,'SIGNATURE_MATCH',?::jsonb)",UUID.randomUUID(),job,location,store.json([catalog:Long.toString(catalog)]))
        store.jdbc.update("UPDATE job SET phase='SIGNATURE_MATCH',signature_catalog_revision=?,pending_work=pending_work+1 WHERE id=?",catalog,job)
        store.jdbc.update('UPDATE scan SET signature_scheduled_revision=greatest(signature_scheduled_revision,?),signature_scheduled_evidence=evidence_revision WHERE id=?',catalog,scan)
        store.event(job,'PHASE_CHANGED','worker',[phase:'SIGNATURE_MATCH',catalogRevision:Long.toString(catalog)])
    }
    /** A committed catalog clock is the durable fan-out request. Coalesce edits; admit one scan at a time. */
    static boolean enqueue(InventoryStore store) {
        long catalog=current(store)
        Map scan=store.one('''SELECT s.id FROM scan s WHERE s.inventory_frozen_at IS NOT NULL AND (s.signature_scheduled_revision<? OR s.signature_scheduled_evidence<s.evidence_revision)
            AND NOT EXISTS(SELECT 1 FROM job j WHERE j.scan_id=s.id AND j.state NOT IN ('COMPLETED','COMPLETED_WITH_ERRORS','CANCELLED','FAILED'))
            ORDER BY s.sequence LIMIT 1 FOR UPDATE''',catalog)
        if (scan==null) return false
        UUID job=UUID.randomUUID()
        store.jdbc.update("INSERT INTO job(id,scan_id,type,phase,state) VALUES (?,?,'SIGNATURE_MATCH','SIGNATURE_MATCH','QUEUED')",job,scan.id)
        stage(store,job,(UUID)scan.id)
        store.event(job,'QUEUED','catalog-rematch',[catalogRevision:Long.toString(catalog),databaseOnly:true])
        true
    }
    void select(WorkClaim c) {
        store.tx.executeWithoutResult { tx ->
            store.fence(c)
            if (store.shouldStop(c)) { store.checkpoint(c); return }
            long catalog=Long.parseLong(c.payload.catalog.toString()),after=Long.parseLong((c.payload.after ?: '0').toString()),cutoff=Long.parseLong(c.payload.cutoff.toString())
            List<Map<String,Object>> rows=store.jdbc.queryForList('''SELECT e.* FROM scan_entry e WHERE e.scan_id=? AND e.sequence>? AND e.sequence<=?
                AND '''+HashPipeline.ELIGIBLE+''' AND EXISTS(SELECT 1 FROM signatures_at(?) r WHERE r.enabled AND r.size_bytes=e.size_bytes)
                AND NOT EXISTS(SELECT 1 FROM accepted_hash h WHERE h.entry_id=e.id) ORDER BY e.sequence LIMIT 500''',c.scanId,after,cutoff,catalog)
            HashPipeline hashing=new HashPipeline(store,null)
            for (Map row : rows) hashing.addHash(c.jobId,row,false,['SIGNATURE_SIZE'])
            if (rows.isEmpty()) {
                store.jdbc.update("UPDATE job SET phase='HASHING' WHERE id=?",c.jobId)
                store.complete(c,null,null,'SIGNATURE_CANDIDATES_FROZEN')
            } else {
                Map payload=new LinkedHashMap(c.payload); payload.put('after',rows.getLast().sequence.toString())
                store.jdbc.update('UPDATE work_item SET payload=?::jsonb WHERE id=?',store.json(payload),c.id)
                store.checkpoint(c)
            }
        }
    }
    void run(WorkClaim c) {
        store.tx.executeWithoutResult { tx ->
            store.fence(c)
            if (store.shouldStop(c)) { store.checkpoint(c); return }
            Map scan=store.one('SELECT * FROM scan WHERE id=? FOR UPDATE',c.scanId)
            Map payload=new LinkedHashMap(c.payload)
            UUID run=payload.run==null ? null : UUID.fromString(payload.run.toString())
            Map revision=run==null ? null : store.one('SELECT * FROM signature_run WHERE id=?',run)
            if (revision!=null && revision.evidence_revision!=scan.evidence_revision) {
                store.jdbc.update("UPDATE signature_run SET state='ABANDONED' WHERE id=?",run)
                run=null; revision=null
            }
            if (run==null) {
                run=UUID.randomUUID()
                long cutoff=store.jdbc.queryForObject('SELECT coalesce(max(sequence),0) FROM scan_entry WHERE scan_id=?',Long,c.scanId)
                store.jdbc.update("INSERT INTO signature_run(id,scan_id,job_id,catalog_revision,evidence_revision,entry_cutoff,state) VALUES (?,?,?,?,?,?,'BUILDING')",run,c.scanId,c.jobId,Long.parseLong(payload.catalog.toString()),scan.evidence_revision,cutoff)
                payload.put('run',run.toString()); payload.put('after','0')
                revision=store.one('SELECT * FROM signature_run WHERE id=?',run)
            }
            List<Map<String,Object>> rows=store.jdbc.queryForList('''SELECT e.*,
                EXISTS(SELECT 1 FROM signatures_at(?) r WHERE r.enabled AND r.size_bytes=e.size_bytes) AS signature_size
                FROM observation_search e WHERE e.scan_id=? AND e.sequence>? AND e.sequence<=? ORDER BY e.sequence LIMIT 100''',revision.catalog_revision,c.scanId,Long.parseLong(payload.after.toString()),revision.entry_cutoff)
            for (Map row : rows) {
                String check=row.stale==Boolean.TRUE ? 'STALE' : row.entry_type!='REGULAR' ? 'CATALOG_NOT_CHECKED' : row.hash_status=='ACCEPTED' ? 'CHECKED_HASH' :
                    row.hash_status in ['FAILED','INELIGIBLE'] ? 'READ_ERROR' : row.signature_size==Boolean.TRUE ? 'HASH_REQUIRED' : 'EXCLUDED_BY_SIZE'
                String match=check in ['CHECKED_HASH','EXCLUDED_BY_SIZE'] ? 'NO_MATCH_IN_CHECKED_CATALOG' : 'UNDETERMINED'
                store.jdbc.update('''INSERT INTO signature_check(run_id,scan_id,entry_id,entry_sequence,attempt_id,check_status,match_status)
                    VALUES (?,?,?,?,?,?,?)''',run,c.scanId,row.id,row.sequence,check=='CHECKED_HASH' ? row.accepted_attempt_id : null,check,match)
                if (check=='CHECKED_HASH') {
                    int matches=store.jdbc.update('''INSERT INTO signature_match(run_id,entry_id,signature_id,signature_revision)
                        SELECT ?,?,r.signature_id,r.revision FROM signatures_at(?) r WHERE r.enabled AND r.size_bytes=? AND r.algorithm='SHA-256' AND r.digest=?
                        AND (r.filename_match_mode='ADVISORY' OR r.filename_bytes=?)''',run,row.id,revision.catalog_revision,row.size_bytes,row.digest,row.name_bytes)
                    if (matches>0) store.jdbc.update("UPDATE signature_check SET match_status='MATCHED' WHERE run_id=? AND entry_id=?",run,row.id)
                }
            }
            if (rows.isEmpty()) {
                store.jdbc.update("UPDATE signature_run SET state='PUBLISHED',published_at=clock_timestamp() WHERE id=?",run)
                // Never replace a newer catalog finding with an older explicitly captured check.
                store.jdbc.update('''UPDATE scan SET current_signature_run_id=?,query_revision=query_revision+1 WHERE id=?
                    AND (current_signature_run_id IS NULL OR (SELECT catalog_revision FROM signature_run WHERE id=current_signature_run_id)<=?)''',run,c.scanId,revision.catalog_revision)
                store.complete(c,null,null,'SIGNATURES_MATCHED')
            } else {
                payload.put('after',rows.getLast().sequence.toString())
                store.jdbc.update('UPDATE work_item SET payload=?::jsonb WHERE id=?',store.json(payload),c.id)
                store.checkpoint(c)
            }
        }
    }
}
