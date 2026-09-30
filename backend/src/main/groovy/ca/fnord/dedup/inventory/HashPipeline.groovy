package ca.fnord.dedup.inventory

import ca.fnord.dedup.roots.SourceDefinition
import ca.fnord.dedup.roots.SourceRegistry
import ca.fnord.dedup.roots.fs.*
import groovy.transform.CompileStatic
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

/** Bounded streams; every filesystem handle is closed before a work result or stop is acknowledged. */
@CompileStatic
class HashPipeline {
    static final String ELIGIBLE = "e.entry_type='REGULAR' AND e.size_bytes IS NOT NULL AND e.discovery_status='OBSERVED' AND NOT EXISTS(SELECT 1 FROM observation_validation v WHERE v.entry_id=e.id) AND NOT EXISTS(SELECT 1 FROM scan s WHERE s.id=e.scan_id AND s.evidence_block_code IS NOT NULL)"
    final InventoryStore store
    final SourceRegistry sources
    HashPipeline(InventoryStore store, SourceRegistry sources) { this.store=store; this.sources=sources }

    void select(WorkClaim c) {
        store.tx.executeWithoutResult { status ->
            store.fence(c)
            if (store.shouldStop(c)) { store.checkpoint(c); return }
            Map p = c.payload
            if (p.stage == null || p.stage == 'SIZES') {
                List<Map<String,Object>> rows = store.jdbc.queryForList('''SELECT e.size_bytes,count(*) AS paths FROM scan_entry e
                    WHERE e.scan_id=? AND e.size_bytes>? AND '''+ELIGIBLE+''' GROUP BY e.size_bytes HAVING count(*)>=2
                    ORDER BY e.size_bytes LIMIT 500''',c.scanId,Long.parseLong((p.after ?: '-1').toString()))
                for (Map row : rows) store.jdbc.update('INSERT INTO candidate_size(scan_id,size_bytes,path_count) VALUES (?,?,?) ON CONFLICT DO NOTHING',c.scanId,row.size_bytes,row.paths)
                save(c,rows.isEmpty() ? [stage:'ENTRIES',after:'0'] : [stage:'SIZES',after:rows.getLast().size_bytes.toString()])
            } else {
                List<Map<String,Object>> rows = store.jdbc.queryForList('''SELECT e.*,cs.size_bytes IS NOT NULL AS duplicate_candidate,
                    (s.options->>'includeSignatureCandidates'='true' AND EXISTS(SELECT 1 FROM signatures_at(s.signature_catalog_revision) r WHERE r.enabled AND r.size_bytes=e.size_bytes)) AS signature_candidate
                    FROM scan_entry e JOIN scan s ON s.id=e.scan_id LEFT JOIN candidate_size cs ON cs.scan_id=e.scan_id AND cs.size_bytes=e.size_bytes
                    WHERE e.scan_id=? AND e.sequence>? AND (cs.size_bytes IS NOT NULL OR (s.options->>'includeSignatureCandidates'='true'
                        AND EXISTS(SELECT 1 FROM signatures_at(s.signature_catalog_revision) r WHERE r.enabled AND r.size_bytes=e.size_bytes))) AND '''+ELIGIBLE+' ORDER BY e.sequence LIMIT 500',c.scanId,Long.parseLong(p.after.toString()))
                for (Map row : rows) {
                    List<String> reasons=new ArrayList<>()
                    if (row.duplicate_candidate==Boolean.TRUE) reasons.add('DUPLICATE_SIZE')
                    if (row.signature_candidate==Boolean.TRUE) reasons.add('SIGNATURE_SIZE')
                    addHash(c.jobId,row,false,reasons)
                }
                if (rows.isEmpty()) {
                    store.jdbc.update('UPDATE scan SET candidates_frozen_at=clock_timestamp() WHERE id=?',c.scanId)
                    store.jdbc.update("UPDATE job SET phase='HASHING' WHERE id=?",c.jobId)
                    store.complete(c,null,null,'CANDIDATES_FROZEN')
                } else save(c,[stage:'ENTRIES',after:rows.getLast().sequence.toString()])
            }
        }
    }
    void addHash(UUID jobId, Map entry, boolean force, List<String> reasons) {
        int added = store.jdbc.update("INSERT INTO work_item(id,job_id,location_id,kind,entry_id,payload) VALUES (?,?,?,'HASH',?,?::jsonb) ON CONFLICT DO NOTHING",
            UUID.randomUUID(),jobId,entry.location_id,entry.id,store.json([forceRehash:force,reasons:reasons]))
        if (added > 0) store.jdbc.update('UPDATE job SET pending_work=pending_work+1,candidate_files=candidate_files+1,candidate_bytes=candidate_bytes+? WHERE id=?',entry.size_bytes,jobId)
    }
    void save(WorkClaim c, Map payload) {
        store.jdbc.update('UPDATE scan SET query_revision=query_revision+1 WHERE id=?',c.scanId)
        store.jdbc.update('UPDATE work_item SET payload=?::jsonb WHERE id=?',store.json(payload),c.id)
        store.checkpoint(c)
    }
    private static final long CONTROL_CHECK_NANOS = TimeUnit.SECONDS.toNanos(10L)

    void hash(WorkClaim c, BooleanSupplier stopping) {
        if (stopping.asBoolean || store.shouldStop(c)) { store.checkpoint(c); return }
        Map entry = store.one('SELECT e.* FROM scan_entry e WHERE e.id=? AND e.scan_id=? AND '+ELIGIBLE,c.entryId,c.scanId)
        if (entry == null) { store.complete(c,'OBSERVATION_INELIGIBLE','This observation is unstable or lacks usable metadata.'); return }
        if (c.payload.forceRehash != Boolean.TRUE && reuse(c)) return

        Instant startedAt = Instant.now()
        FileMetadata pre = null, post = null
        byte[] digest
        long bytesRead = 0L
        long nextControlCheck = System.nanoTime() + CONTROL_CHECK_NANOS
        SourceDefinition source = store.mapper.readValue(c.sourceSnapshot,SourceDefinition)
        Map scanOptions = store.parse((String)store.one('SELECT options::text AS options FROM scan WHERE id=?',c.scanId).options)
        boolean unsafeFast = source.unsafeFast || scanOptions.unsafeFast == Boolean.TRUE
        if (unsafeFast) source.unsafeFast = true
        try {
            FileMetadata expected = metadata(entry)
            try (ReadOnlyFileAccess.Root root = sources.openValidated(source)) {
                if (!unsafeFast) store.acceptRoot(c,root.identity())
                try (ReadOnlyFileAccess.RegularFile file = root.openRegular(c.path,expected)) {
                    pre = file.metadata()
                    if (!unsafeFast && !expected.sameFingerprint(pre)) throw new SourceAccessException('CHANGED','The opened file differs from its inventory observation.')
                    MessageDigest sha = MessageDigest.getInstance('SHA-256')
                    byte[] buffer = new byte[1024*1024]
                    while (true) {
                        if (stopping.asBoolean) throw new InventoryStop()
                        long now = System.nanoTime()
                        if (now >= nextControlCheck) {
                            if (store.shouldStop(c)) throw new InventoryStop()
                            nextControlCheck = now + CONTROL_CHECK_NANOS
                        }
                        int count = file.read(buffer)
                        if (count == -1) break
                        if (count <= 0) throw new SourceAccessException('READ_FAILED','The source returned an incomplete read.')
                        sha.update(buffer,0,count)
                        bytesRead = Math.addExact(bytesRead,(long)count)
                    }
                    post = file.metadata()
                    if (!unsafeFast && (!expected.sameFingerprint(post) || !file.validateComplete() || bytesRead != expected.size))
                        throw new SourceAccessException('CHANGED','Size, metadata, EOF, or pathname validation failed.')
                    if (stopping.asBoolean || store.shouldStop(c)) throw new InventoryStop()
                    if (!unsafeFast) {
                        try (ReadOnlyFileAccess.Root current = sources.openValidated(source)) {
                            if (!root.identity().sameObject(current.identity())) throw new JobProblem(409,'SOURCE_CONFIGURATION_CHANGED','The source root binding changed.')
                            store.acceptRoot(c,current.identity())
                        }
                    }
                    digest = sha.digest()
                }
            }
        } catch (InventoryStop ignored) {
            // No partial hash state or read progress is persisted. Resume retries this file from byte zero.
            store.checkpoint(c)
            return
        } catch (SourceAccessException e) {
            store.tx.executeWithoutResult { status ->
                invalidateFailedRead(c,e.code)
                if (e.code in ['WRITABLE_SOURCE','APPLICATION_STORAGE_OVERLAP']) store.block(c,e.code)
                else store.complete(c,e.code,e.message)
            }
            return
        } catch (JobProblem e) {
            if (e.code == 'SOURCE_CONFIGURATION_CHANGED') store.invalidateScanEvidence(c.scanId,e.code,c)
            store.tx.executeWithoutResult { status ->
                invalidateFailedRead(c,e.code)
                store.block(c,e.code)
            }
            return
        }

        UUID attempt = UUID.randomUUID()
        store.tx.executeWithoutResult { status ->
            String outcome = finishAttempt(c,attempt,'ACCEPTED',digest,null,null,pre,post,bytesRead,startedAt,unsafeFast)
            store.complete(c,outcome == 'ACCEPTED' ? null : 'HASH_CONFLICT',
                outcome == 'ACCEPTED' ? null : 'The observation has conflicting evidence. Start a new scan to establish fresh observations.',outcome)
        }
    }

    private boolean reuse(WorkClaim c) {
        store.tx.execute { status ->
            store.fence(c)
            store.one('SELECT id FROM scan WHERE id=? FOR UPDATE',c.scanId)
            Map accepted = store.one('SELECT h.attempt_id FROM accepted_hash h JOIN scan_entry e ON e.id=h.entry_id WHERE h.entry_id=? AND h.active AND '+ELIGIBLE,c.entryId)
            if (accepted == null) return false
            store.jdbc.update('UPDATE job SET reused_files=reused_files+1 WHERE id=?',c.jobId)
            store.complete(c,null,null,'REUSED')
            true
        }
    }

    /**
     * Publishes only a completed file-level hash result. There is deliberately
     * no READ/PROGRESS row: unfinished reads leave no hash_attempt behind.
     */
    String finishAttempt(WorkClaim c, UUID attempt, String requested, byte[] digest, String code, String detail,
                         FileMetadata pre, FileMetadata post, long bytesRead, Instant startedAt, boolean unsafeFast = false) {
        store.tx.execute { status ->
            store.fence(c)
            store.one('SELECT id FROM scan WHERE id=? FOR UPDATE',c.scanId)
            store.jdbc.update('UPDATE scan SET query_revision=query_revision+1 WHERE id=?',c.scanId)
            Map old = store.one('SELECT h.*,a.digest FROM accepted_hash h JOIN hash_attempt a ON a.id=h.attempt_id WHERE h.entry_id=?',c.entryId)
            String outcome = requested
            String errorCode = code, errorDetail = detail
            if (outcome == 'ACCEPTED') {
                Map observation = store.one('SELECT e.* FROM scan_entry e WHERE e.id=? AND '+ELIGIBLE,c.entryId)
                if (observation == null || (!unsafeFast && (pre == null || post == null || !metadata(observation).sameFingerprint(pre) || !metadata(observation).sameFingerprint(post) ||
                    new BigDecimal(bytesRead) != new BigDecimal(observation.size_bytes.toString()))) ||
                    (old != null && (!((Boolean)old.active) || !MessageDigest.isEqual((byte[])old.digest,digest)))) {
                    outcome='CONFLICT'; errorCode='HASH_CONFLICT'; errorDetail='Fresh evidence contradicts or cannot validate the original observation.'
                }
            }

            store.jdbc.update('''INSERT INTO hash_attempt(id,scan_id,entry_id,job_id,work_id,lease_token,reasons,started_at,completed_at,bytes_read,outcome,
                pre_fingerprint,post_fingerprint,digest,error_code,error_detail)
                VALUES (?,?,?,?,?,?,?::jsonb,?,clock_timestamp(),?,?,?::jsonb,?::jsonb,?,?,?)''',
                attempt,c.scanId,c.entryId,c.jobId,c.id,c.token,store.json(c.payload.reasons),Timestamp.from(startedAt),bytesRead,outcome,
                pre == null ? null : fingerprint(pre),post == null ? null : fingerprint(post),outcome == 'ACCEPTED' ? digest : null,errorCode,errorDetail)

            if (outcome == 'ACCEPTED') {
                store.jdbc.update("INSERT INTO accepted_hash(entry_id,scan_id,algorithm,attempt_id) VALUES (?,?,'SHA-256',?) ON CONFLICT (entry_id,algorithm) DO UPDATE SET attempt_id=excluded.attempt_id",c.entryId,c.scanId,attempt)
                store.jdbc.update('UPDATE scan SET evidence_revision=evidence_revision+1 WHERE id=?',c.scanId)
                store.jdbc.update('''UPDATE job SET hashed_files=hashed_files+1,
                    physical_bytes_read=physical_bytes_read+?,useful_bytes_hashed=useful_bytes_hashed+? WHERE id=?''',bytesRead,bytesRead,c.jobId)
            } else if (outcome == 'CONFLICT') {
                int invalidated = store.jdbc.update('UPDATE accepted_hash SET active=false,invalidated_at=clock_timestamp(),invalidation_code=? WHERE entry_id=? AND active',errorCode,c.entryId)
                store.jdbc.update('INSERT INTO observation_validation(entry_id,outcome,observed_fingerprint) VALUES (?,\'HASH_CONFLICT\',?::jsonb) ON CONFLICT DO NOTHING',
                    c.entryId,store.json([code:errorCode]))
                if (invalidated > 0 || outcome == 'CONFLICT') store.jdbc.update('UPDATE scan SET evidence_revision=evidence_revision+1 WHERE id=?',c.scanId)
            }
            store.fence(c)
            outcome
        }
    }

    private void invalidateFailedRead(WorkClaim c, String errorCode) {
        store.fence(c)
        store.one('SELECT id FROM scan WHERE id=? FOR UPDATE',c.scanId)
        store.jdbc.update('UPDATE scan SET query_revision=query_revision+1 WHERE id=?',c.scanId)
        int invalidated = store.jdbc.update('UPDATE accepted_hash SET active=false,invalidated_at=clock_timestamp(),invalidation_code=? WHERE entry_id=? AND active',errorCode,c.entryId)
        boolean changed = errorCode in ['CHANGED','SOURCE_CONFIGURATION_CHANGED']
        if (changed) store.jdbc.update('INSERT INTO observation_validation(entry_id,outcome,observed_fingerprint) VALUES (?,\'HASH_CHANGED\',?::jsonb) ON CONFLICT DO NOTHING',
            c.entryId,store.json([code:errorCode]))
        if (invalidated > 0 || changed) store.jdbc.update('UPDATE scan SET evidence_revision=evidence_revision+1 WHERE id=?',c.scanId)
        store.fence(c)
    }

    private String fingerprint(FileMetadata m) { store.json(new InventoryEntry(metadata:m).fingerprint()) }
    static FileMetadata metadata(Map row) {
        new FileMetadata(((Number)row.metadata_mask).intValue(),((Number)row.mode).intValue(),((Number)row.inode).longValue(),((Number)row.size_bytes).longValue(),
            ((Number)row.mount_id).longValue(),((Number)row.device_major).longValue(),((Number)row.device_minor).longValue(),((Number)row.mtime_seconds).longValue(),
            ((Number)row.mtime_nanos).intValue(),((Number)row.ctime_seconds).longValue(),((Number)row.ctime_nanos).intValue(),
            ((Number)row.birth_seconds)?.longValue(),((Number)row.birth_nanos)?.intValue(),((Number)row.link_count)?.longValue(),((Number)row.blocks)?.longValue(),((Number)row.uid)?.longValue(),((Number)row.gid)?.longValue())
    }
}
