package ca.fnord.dedup.inventory

import ca.fnord.dedup.signatures.SignatureExports
import ca.fnord.dedup.signatures.SignaturePipeline
import ca.fnord.dedup.roots.SourceDefinition
import ca.fnord.dedup.roots.fs.FileMetadata
import ca.fnord.dedup.roots.fs.RawPath
import groovy.transform.CompileStatic
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant

/** Transactions here contain database work only. Lock order: scheduler, job, work. */
@CompileStatic
@Repository
class InventoryStore {
    final JdbcTemplate jdbc
    final TransactionTemplate tx
    final ObjectMapper mapper
    static final Set<String> TERMINAL = Set.of('COMPLETED','COMPLETED_WITH_ERRORS','CANCELLED','FAILED')

    InventoryStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper) {
        this.jdbc = jdbc; this.tx = tx; this.mapper = mapper
    }

    /**
     * Read-only scheduler wake check. When this is false the worker must not
     * acquire/renew the scheduler lease, avoiding idle WAL writes.
     */
    boolean schedulerWorkPending() {
        Boolean pending = jdbc.queryForObject('''
            SELECT EXISTS (
                SELECT 1 FROM job
                WHERE state IN ('QUEUED','RUNNING','PAUSE_REQUESTED','CANCEL_REQUESTED')
            ) OR EXISTS (
                SELECT 1 FROM signature_export
                WHERE state IN ('QUEUED','BUILDING')
            ) OR EXISTS (
                SELECT 1
                FROM scan s CROSS JOIN signature_clock c
                WHERE c.id=1
                  AND s.inventory_frozen_at IS NOT NULL
                  AND (s.signature_scheduled_revision<c.revision OR s.signature_scheduled_evidence<s.evidence_revision)
                  AND NOT EXISTS (
                      SELECT 1 FROM job j
                      WHERE j.scan_id=s.id
                        AND j.state NOT IN ('COMPLETED','COMPLETED_WITH_ERRORS','CANCELLED','FAILED')
                  )
            )
        ''', Boolean)
        pending == Boolean.TRUE
    }
    String json(Object value) { mapper.writeValueAsString(value) }
    Map<String,Object> parse(String value) { (Map<String,Object>)mapper.readValue(value, Map) }
    Map<String,Object> one(String sql, Object... args) {
        List<Map<String,Object>> rows = jdbc.queryForList(sql, args)
        rows.isEmpty() ? null : rows.getFirst()
    }
    Map<String,Object> job(UUID id, boolean lock = false) {
        Map<String,Object> row = one('SELECT * FROM job WHERE id=?' + (lock ? ' FOR UPDATE' : ''), id)
        if (row == null) throw new JobProblem(404, 'JOB_NOT_FOUND', 'The job does not exist.')
        row
    }
    void event(UUID jobId, String kind, String actor, Map details = Map.of()) {
        jdbc.update('INSERT INTO job_event(job_id,event_type,actor,details) VALUES (?,?,?,?::jsonb)', jobId,kind,actor,json(details))
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override void afterCommit() {
                    LoggerFactory.getLogger(InventoryStore).atInfo().addKeyValue('jobId',jobId.toString())
                        .addKeyValue('event',kind).log('Inventory job event committed.')
                }
            })
        }
    }
    void state(UUID id, String state, String actor, String code = null) {
        jdbc.update('''UPDATE job SET state=?, block_code=?, version=version+1, updated_at=clock_timestamp(),
            finished_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END WHERE id=?''', state,code,TERMINAL.contains(state),id)
        jdbc.update('UPDATE scan SET query_revision=query_revision+1 WHERE id=(SELECT scan_id FROM job WHERE id=?)',id)
        event(id,state,actor,code == null ? Map.of() : [code:code])
    }

    /** Returns a new fencing epoch only after the previous owner's DB lease expires. */
    Long acquire(UUID owner) {
        tx.execute { status ->
            Map row = one('SELECT *,expires_at>clock_timestamp() AS valid FROM scheduler_lock WHERE id=1 FOR UPDATE')
            if (row.owner == owner && row.valid == Boolean.TRUE) {
                // Re-entered only after this process's iteration failed and closed its handles.
                recoverLocked()
                return ((Number)row.token).longValue()
            }
            Map acquired = one('''UPDATE scheduler_lock SET owner=?, token=token+1,
                expires_at=clock_timestamp()+interval '60 seconds'
                WHERE id=1 AND expires_at <= clock_timestamp() RETURNING token''',owner)
            if (acquired == null) return null
            recoverLocked()
            ((Number)acquired.token).longValue()
        }
    }
    private void coordinator(UUID owner, long token) {
        if (one('''SELECT id FROM scheduler_lock WHERE id=1 AND owner=? AND token=?
            AND expires_at > clock_timestamp() FOR UPDATE''',owner,token) == null) throw new LeaseLost()
    }
    boolean heartbeat(UUID owner, long token) {
        try {
            tx.execute { status ->
                coordinator(owner,token)
                jdbc.update("UPDATE scheduler_lock SET expires_at=clock_timestamp()+interval '60 seconds' WHERE id=1")
                // Never resurrect a work lease that already expired.
                jdbc.update('''UPDATE work_item SET lease_expires_at=clock_timestamp()+interval '60 seconds'
                    WHERE state='LEASED' AND lease_owner=? AND lease_expires_at>clock_timestamp()''',owner)
                jdbc.update("UPDATE job SET heartbeat_at=clock_timestamp() WHERE state IN ('RUNNING','PAUSE_REQUESTED','CANCEL_REQUESTED')")
                true
            }
        } catch (LeaseLost ignored) { false }
    }
    private void recoverLocked() {
        List<Map<String,Object>> active = jdbc.queryForList("SELECT * FROM job WHERE state IN ('RUNNING','PAUSE_REQUESTED','CANCEL_REQUESTED') FOR UPDATE")
        for (Map row : active) {
            UUID id = (UUID)row.id
            jdbc.update("UPDATE hash_attempt SET outcome='INTERRUPTED',completed_at=clock_timestamp(),error_code='PROCESS_INTERRUPTED' WHERE job_id=? AND outcome='READING'",id)
            jdbc.update("UPDATE work_item SET state='READY', lease_token=lease_token+1, lease_owner=NULL, lease_expires_at=NULL WHERE job_id=? AND state='LEASED'",id)
            if (row.state == 'CANCEL_REQUESTED') cancelLocked(id,'recovery')
            else state(id,'INTERRUPTED','recovery','PROCESS_INTERRUPTED')
        }
    }
    void release(UUID owner, long token) {
        tx.executeWithoutResult { status ->
            coordinator(owner,token)
            recoverLocked()
            jdbc.update("UPDATE scheduler_lock SET owner=NULL, expires_at='-infinity' WHERE id=1")
        }
    }

    WorkClaim claim(UUID owner, long epoch) {
        tx.execute { status ->
            coordinator(owner,epoch)
            Map active = one("SELECT * FROM job WHERE state IN ('RUNNING','PAUSE_REQUESTED','CANCEL_REQUESTED') ORDER BY sequence LIMIT 1 FOR UPDATE")
            if (active != null && active.state != 'RUNNING') {
                settleControl((UUID)active.id)
                return null
            }
            if (active == null) {
                active = one("SELECT * FROM job WHERE state='QUEUED' ORDER BY sequence LIMIT 1 FOR UPDATE SKIP LOCKED")
                if (active == null) { if (!SignatureExports.advance(this)) SignaturePipeline.enqueue(this); coordinator(owner,epoch); return null }
                state((UUID)active.id,'RUNNING','scheduler')
                jdbc.update('UPDATE job SET started_at=coalesce(started_at,clock_timestamp()),heartbeat_at=clock_timestamp() WHERE id=?',active.id)
            }
            UUID jobId = (UUID)active.id
            // An expired worker must be fenced and explicitly resumed, not silently retried.
            if (one("SELECT id FROM work_item WHERE job_id=? AND state='LEASED' AND lease_expires_at<=clock_timestamp() LIMIT 1",jobId) != null) {
                jdbc.update("UPDATE work_item SET state='READY',lease_token=lease_token+1,lease_owner=NULL,lease_expires_at=NULL WHERE job_id=? AND state='LEASED'",jobId)
                jdbc.update("UPDATE hash_attempt SET outcome='INTERRUPTED',completed_at=clock_timestamp(),error_code='WORK_LEASE_EXPIRED' WHERE job_id=? AND outcome='READING'",jobId)
                state(jobId,'INTERRUPTED','scheduler','WORK_LEASE_EXPIRED')
                return null
            }
            Map work = one("SELECT * FROM work_item WHERE job_id=? AND state='READY' ORDER BY sequence LIMIT 1 FOR UPDATE SKIP LOCKED",jobId)
            if (work == null) {
                if (((Number)active.pending_work).longValue() == 0L) finishJob(jobId,(UUID)active.scan_id)
                return null
            }
            Map row = one('''UPDATE work_item SET state='LEASED',attempts=attempts+1,lease_owner=?,lease_token=lease_token+1,
                lease_expires_at=clock_timestamp()+interval '60 seconds' WHERE id=? RETURNING lease_token''',owner,work.id)
            Map target = one('''SELECT l.*,s.configuration_revision,ss.snapshot::text AS snapshot,ss.root_identity::text AS root_identity
                FROM file_location l JOIN scan_source ss ON ss.root_location_id IS NOT NULL AND ss.source_id=l.source_id
                    AND ss.source_instance_id=l.source_instance_id AND ss.scan_id=?
                JOIN scan s ON s.id=ss.scan_id WHERE l.id=?''',active.scan_id,work.location_id)
            jdbc.update('UPDATE job SET current_source_id=?,current_location_id=?,updated_at=clock_timestamp() WHERE id=?',target.source_id,work.location_id,jobId)
            new WorkClaim(id:(UUID)work.id,kind:(String)work.kind,entryId:(UUID)work.entry_id,payload:parse(work.payload.toString()),jobId:jobId,scanId:(UUID)active.scan_id,locationId:(UUID)work.location_id,
                sourceId:(UUID)target.source_id,sourceInstanceId:(UUID)target.source_instance_id,owner:owner,schedulerToken:epoch,
                token:((Number)row.lease_token).longValue(),path:(byte[])target.relative_path_bytes,
                configurationRevision:(String)target.configuration_revision,sourceSnapshot:(String)target.snapshot,rootIdentity:(String)target.root_identity)
        }
    }
    Map<String,Object> fence(WorkClaim claim) {
        coordinator(claim.owner,claim.schedulerToken)
        Map<String,Object> j = job(claim.jobId,true)
        if (!(j.state in ['RUNNING','PAUSE_REQUESTED','CANCEL_REQUESTED'])) throw new LeaseLost()
        if (one('''SELECT id FROM work_item WHERE id=? AND state='LEASED' AND lease_owner=? AND lease_token=?
            AND lease_expires_at>clock_timestamp() FOR UPDATE''',claim.id,claim.owner,claim.token) == null) throw new LeaseLost()
        j
    }
    boolean shouldStop(WorkClaim c) {
        Map row = one('''SELECT j.state FROM job j JOIN work_item w ON w.job_id=j.id JOIN scheduler_lock s ON s.id=1
            WHERE w.id=? AND w.state='LEASED' AND w.lease_owner=? AND w.lease_token=? AND w.lease_expires_at>clock_timestamp()
            AND s.owner=? AND s.token=? AND s.expires_at>clock_timestamp()''',c.id,c.owner,c.token,c.owner,c.schedulerToken)
        if (row == null) throw new LeaseLost()
        row.state != 'RUNNING'
    }
    void checkpoint(WorkClaim c) {
        tx.executeWithoutResult { status ->
            fence(c)
            jdbc.update("UPDATE work_item SET state='READY',lease_owner=NULL,lease_expires_at=NULL,checkpoint_at=clock_timestamp() WHERE id=?",c.id)
            jdbc.update('UPDATE job SET checkpoint_at=clock_timestamp() WHERE id=?',c.jobId)
            settleControl(c.jobId)
        }
    }
    private void settleControl(UUID id) {
        Map row = job(id,true)
        if (one("SELECT id FROM work_item WHERE job_id=? AND state='LEASED' LIMIT 1",id) != null) return
        if (row.state == 'CANCEL_REQUESTED') cancelLocked(id,'worker')
        else if (row.state == 'PAUSE_REQUESTED') state(id,'PAUSED','worker',(String)row.block_code)
    }
    void block(WorkClaim c, String code) {
        tx.executeWithoutResult { status ->
            Map row = fence(c)
            if (row.state != 'CANCEL_REQUESTED') state(c.jobId,'PAUSE_REQUESTED','worker',code)
            jdbc.update("UPDATE work_item SET state='READY',lease_owner=NULL,lease_expires_at=NULL WHERE id=?",c.id)
            settleControl(c.jobId)
        }
    }
    private void cancelLocked(UUID id, String actor) {
        // No tree-wide updates: terminal job state fences its outstanding work.
        state(id,'CANCELLED',actor)
        jdbc.update('UPDATE job SET current_source_id=NULL,current_location_id=NULL WHERE id=?',id)
    }
    private void finishJob(UUID id, UUID scanId) {
        Map row = job(id,true)
        if (row.state != 'RUNNING') { settleControl(id); return }
        if (one("SELECT id FROM work_item WHERE job_id=? AND state IN ('READY','LEASED') LIMIT 1",id) != null) return
        if (row.type == 'SCAN' && row.phase == 'INVENTORY') {
            jdbc.update('UPDATE scan SET inventory_frozen_at=clock_timestamp() WHERE id=?',scanId)
            stage(id,scanId,'CANDIDATE_SELECTION','SELECT_CANDIDATES')
            return
        }
        if (row.type in ['SCAN','HASH','SIGNATURE_CHECK'] && row.phase in ['CANDIDATE_SELECTION','HASHING']) {
            stage(id,scanId,'ANALYSIS','GROUP')
            return
        }
        if (row.type in ['SCAN','HASH','SIGNATURE_CHECK'] && row.phase == 'ANALYSIS') {
            SignaturePipeline.stage(this,id,scanId)
            return
        }
        state(id,((Number)row.error_count).longValue() > 0L || ((Number)row.skipped_entries).longValue() > 0L ? 'COMPLETED_WITH_ERRORS' : 'COMPLETED','worker')
        if (row.type == 'INVENTORY') jdbc.update('UPDATE scan SET inventory_frozen_at=clock_timestamp() WHERE id=?',scanId)
        jdbc.update('UPDATE job SET current_source_id=NULL,current_location_id=NULL,checkpoint_at=clock_timestamp() WHERE id=?',id)
    }

    private void stage(UUID id, UUID scanId, String phase, String kind) {
        UUID location = (UUID)one('SELECT root_location_id FROM scan_source WHERE scan_id=? ORDER BY source_id LIMIT 1',scanId).root_location_id
        jdbc.update('INSERT INTO work_item(id,job_id,location_id,kind) VALUES (?,?,?,?)',UUID.randomUUID(),id,location,kind)
        jdbc.update('UPDATE job SET phase=?,pending_work=pending_work+1 WHERE id=?',phase,id)
        event(id,'PHASE_CHANGED','worker',[phase:phase])
    }

    /** A changed captured configuration/root invalidates this observation window in O(1). */
    void invalidateScanEvidence(UUID scanId, String code, WorkClaim claim = null) {
        tx.executeWithoutResult { status ->
            if (claim != null) fence(claim)
            one('SELECT id FROM scan WHERE id=? FOR UPDATE',scanId)
            jdbc.update('UPDATE scan SET evidence_block_code=?,evidence_revision=evidence_revision+1 WHERE id=? AND evidence_block_code IS NULL',code,scanId)
            if (claim != null) fence(claim)
        }
    }

    void acceptRoot(WorkClaim c, FileMetadata identity) {
        tx.executeWithoutResult { status ->
            fence(c)
            String value = json(InventoryEntry.identity(identity))
            Map row = one('SELECT root_identity IS NULL OR root_identity=?::jsonb AS matches FROM scan_source WHERE scan_id=? AND source_id=?',value,c.scanId,c.sourceId)
            if (row.matches != Boolean.TRUE) throw new JobProblem(409,'SOURCE_CONFIGURATION_CHANGED','The mounted root identity changed. Start a new scan for a replacement dataset.')
            jdbc.update('UPDATE scan_source SET root_identity=coalesce(root_identity,?::jsonb) WHERE scan_id=? AND source_id=?',value,c.scanId,c.sourceId)
        }
    }

    /** The first committed metadata wins. Replays add evidence, never replace facts. */
    boolean batch(WorkClaim c, List<InventoryEntry> entries) {
        if (entries.size() > 500) throw new IllegalArgumentException('Inventory batch exceeds 500 entries.')
        tx.execute { status ->
            fence(c)
            boolean stable = true
            BatchCounters counters = new BatchCounters()
            for (InventoryEntry entry : entries) stable &= persist(c,entry,counters)
            if (counters.entries > 0L) {
                jdbc.update('''UPDATE job SET discovered_entries=discovered_entries+?,discovered_files=discovered_files+?,
                    discovered_directories=discovered_directories+?,discovered_bytes=discovered_bytes+?,skipped_entries=skipped_entries+?,
                    checkpoint_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=?''',
                    counters.entries,counters.files,counters.directories,counters.bytes,counters.skipped,c.jobId)
            } else {
                jdbc.update('UPDATE job SET checkpoint_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=?',c.jobId)
            }
            jdbc.update('UPDATE work_item SET checkpoint_at=clock_timestamp() WHERE id=?',c.id)
            fence(c)
            stable
        }
    }
    UUID location(UUID sourceId, UUID instanceId, UUID parentId, byte[] name, byte[] path) {
        UUID id = UUID.randomUUID()
        Map inserted = one('''INSERT INTO file_location(id,source_id,source_instance_id,parent_id,name_bytes,relative_path_bytes,display_name,display_path,extension)
            VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (source_id,source_instance_id,parent_id,name_bytes) DO NOTHING RETURNING id''',
            id,sourceId,instanceId,parentId,name,path,name.length == 0 ? '/' : RawPath.display(name),path.length == 0 ? '/' : RawPath.display(path),extension(name))
        if (inserted != null) return (UUID)inserted.id
        (UUID)one('SELECT id FROM file_location WHERE source_id=? AND source_instance_id=? AND parent_id IS NOT DISTINCT FROM ? AND name_bytes=?',sourceId,instanceId,parentId,name).id
    }
    private boolean persist(WorkClaim c, InventoryEntry e, BatchCounters counters) {
        UUID locationId = Arrays.equals(e.path,c.path) ? c.locationId : location(c.sourceId,c.sourceInstanceId,e.parentId,e.name,e.path)
        FileMetadata m = e.metadata
        String fp = json(e.fingerprint())
        String coverage = e.type() == 'DIRECTORY' ? (e.excluded ? 'EXCLUDED_BY_POLICY' : 'PARTIAL') : null
        Map inserted = one('''INSERT INTO scan_entry(id,scan_id,location_id,source_id,source_instance_id,entry_type,size_bytes,metadata_mask,mode,
            inode,mount_id,device_major,device_minor,mtime_seconds,mtime_nanos,ctime_seconds,ctime_nanos,birth_seconds,birth_nanos,mtime,ctime,
            link_count,blocks,uid,gid,filesystem_type,symlink_target,discovery_status,directory_coverage,fingerprint,observed_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT (scan_id,location_id) DO NOTHING RETURNING id''',
            UUID.randomUUID(),c.scanId,locationId,c.sourceId,c.sourceInstanceId,e.type(),m?.isRegular() && m.size >= 0L ? m.size : null,m?.mask,m?.mode,
            unsigned(m?.inode),unsigned(m?.mountId),m?.deviceMajor,m?.deviceMinor,m?.mtimeSeconds,m?.mtimeNanos,m?.ctimeSeconds,m?.ctimeNanos,
            m?.birthSeconds,m?.birthNanos,m == null ? null : timestamp(m.mtimeSeconds,m.mtimeNanos),m == null ? null : timestamp(m.ctimeSeconds,m.ctimeNanos),
            unsigned(m?.linkCount),unsigned(m?.blocks),m?.uid,m?.gid,e.filesystem,e.linkTarget,e.errorCode ?: (e.excluded ? 'EXCLUDED_BY_POLICY' : 'OBSERVED'),coverage,fp,Timestamp.from(e.observedAt))
        Map original = one('SELECT id,fingerprint=?::jsonb AS matches FROM scan_entry WHERE scan_id=? AND location_id=?',fp,c.scanId,locationId)
        boolean stable = original.matches == Boolean.TRUE
        if (!stable) {
            jdbc.update('UPDATE scan SET query_revision=query_revision+1 WHERE id=?',c.scanId)
            jdbc.update("INSERT INTO observation_validation(entry_id,outcome,observed_fingerprint) VALUES (?,'UNSTABLE',?::jsonb) ON CONFLICT DO NOTHING",original.id,fp)
            error(c,locationId,'UNSTABLE','Metadata differed on directory replay; the first committed observation was retained.')
        }
        if (inserted != null) {
            counters.entries++
            if (e.type() == 'REGULAR') counters.files++
            if (e.type() == 'DIRECTORY') counters.directories++
            if (m?.isRegular() && m.size >= 0L) counters.bytes = counters.bytes.add(new BigDecimal(m.size))
            if (e.excluded) counters.skipped++
            if (e.type() == 'DIRECTORY' && !e.excluded && e.errorCode == null && locationId != c.locationId) {
                if (addWork(c.jobId,locationId)) jdbc.update('UPDATE work_item SET unresolved_children=unresolved_children+1 WHERE id=?',c.id)
            }
        }
        if (e.errorCode != null) error(c,locationId,e.errorCode,e.errorDetail ?: 'Metadata could not be read.')
        if (e.excluded) jdbc.update('UPDATE work_item SET has_issues=true WHERE id=?',c.id)
        stable
    }
    @CompileStatic
    private static final class BatchCounters {
        long entries
        long files
        long directories
        long skipped
        BigDecimal bytes = BigDecimal.ZERO
    }

    boolean addWork(UUID jobId, UUID locationId) {
        int count = jdbc.update("INSERT INTO work_item(id,job_id,location_id,kind) VALUES (?,?,?,'DIRECTORY') ON CONFLICT DO NOTHING",UUID.randomUUID(),jobId,locationId)
        if (count > 0) jdbc.update('UPDATE job SET pending_work=pending_work+1 WHERE id=?',jobId)
        count > 0
    }
    private void error(WorkClaim c, UUID location, String code, String detail) {
        int count = jdbc.update('INSERT INTO job_error(job_id,location_id,code,detail) VALUES (?,?,?,?) ON CONFLICT DO NOTHING',c.jobId,location,code,detail)
        if (count > 0) jdbc.update('UPDATE job SET error_count=error_count+1 WHERE id=?',c.jobId)
        jdbc.update('UPDATE work_item SET has_issues=true WHERE id=?',c.id)
    }
    void complete(WorkClaim c, String code = null, String detail = null, String outcome = 'ENUMERATED') {
        tx.executeWithoutResult { status ->
            fence(c)
            if (code != null) error(c,c.locationId,code,detail ?: 'Directory enumeration failed.')
            jdbc.update("UPDATE work_item SET state=?,outcome=?,lease_owner=NULL,lease_expires_at=NULL WHERE id=?",code == null ? 'DONE' : 'ERROR',code ?: outcome,c.id)
            jdbc.update('UPDATE job SET pending_work=pending_work-1,completed_work=completed_work+1,checkpoint_at=clock_timestamp() WHERE id=?',c.jobId)
            jdbc.update('UPDATE scan SET query_revision=query_revision+1 WHERE id=?',c.scanId)
            if (c.kind == 'DIRECTORY') resolveCoverage(c,c.locationId)
            settleControl(c.jobId)
        }
    }
    private void resolveCoverage(WorkClaim c, UUID location) {
        // One ancestor at a time, no whole-tree aggregation or in-memory catalog.
        UUID current = location
        while (current != null) {
            Map row = one('''SELECT w.*,l.parent_id FROM work_item w JOIN file_location l ON l.id=w.location_id
                WHERE w.job_id=? AND w.location_id=? AND w.kind='DIRECTORY' FOR UPDATE OF w''',c.jobId,current)
            if (row == null || row.subtree_resolved == Boolean.TRUE || !(row.state in ['DONE','ERROR']) || ((Number)row.unresolved_children).longValue() != 0L) return
            String coverage = row.has_issues == Boolean.TRUE ? 'PARTIAL' : 'COMPLETE'
            if (row.parent_id == null && row.state == 'ERROR' && one('SELECT id FROM scan_entry WHERE scan_id=? AND location_id=? AND entry_type=?',c.scanId,current,'DIRECTORY') == null) coverage = 'UNAVAILABLE'
            jdbc.update('UPDATE work_item SET subtree_resolved=true WHERE id=?',row.id)
            jdbc.update("UPDATE scan_entry SET directory_coverage=? WHERE scan_id=? AND location_id=? AND entry_type='DIRECTORY'",coverage,c.scanId,current)
            if (row.parent_id == null) jdbc.update('UPDATE scan_source SET coverage=? WHERE scan_id=? AND source_id=?',coverage,c.scanId,c.sourceId)
            else jdbc.update('UPDATE work_item SET unresolved_children=unresolved_children-1,has_issues=has_issues OR ? WHERE job_id=? AND location_id=?',row.has_issues,c.jobId,row.parent_id)
            current = (UUID)row.parent_id
        }
    }
    static BigDecimal unsigned(Long value) { value == null ? null : new BigDecimal(Long.toUnsignedString(value)) }
    static Timestamp timestamp(long seconds, int nanos) { Timestamp.from(Instant.ofEpochSecond(seconds,nanos)) }
    private static String extension(byte[] name) {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(name)).toString()
            int dot = decoded.lastIndexOf('.')
            return dot > 0 && dot < decoded.length()-1 ? decoded.substring(dot+1) : null
        } catch (java.nio.charset.CharacterCodingException ignored) { return null }
    }
}
