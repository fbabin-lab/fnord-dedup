package ca.fnord.dedup.inventory

import groovy.transform.CompileStatic

/** Immutable captured attempts, bounded stages, and a single atomic publication pointer. */
@CompileStatic
class AnalysisPipeline {
    final InventoryStore store
    AnalysisPipeline(InventoryStore store) { this.store=store }
    void run(WorkClaim c) {
        store.tx.executeWithoutResult { status ->
            store.fence(c)
            if (store.shouldStop(c)) { store.checkpoint(c); return }
            Map scan = store.one('SELECT * FROM scan WHERE id=? FOR UPDATE',c.scanId)
            Map p = c.payload
            UUID revision = p.revision == null ? null : UUID.fromString(p.revision.toString())
            if (revision != null) {
                Map prior = store.one('SELECT * FROM analysis_revision WHERE id=?',revision)
                if (prior.evidence_revision != scan.evidence_revision) {
                    store.jdbc.update("UPDATE analysis_revision SET state='ABANDONED' WHERE id=?",revision)
                    revision = null
                }
            }
            if (revision == null) {
                revision=UUID.randomUUID()
                store.jdbc.update("INSERT INTO analysis_revision(id,scan_id,job_id,evidence_revision,state) VALUES (?,?,?,?,'BUILDING')",revision,c.scanId,c.jobId,scan.evidence_revision)
                save(c,[revision:revision.toString(),stage:'CAPTURE',after:'0'])
                return
            }
            if (p.stage == 'CAPTURE') {
                List<Map<String,Object>> rows = store.jdbc.queryForList('''SELECT e.id,e.sequence,e.size_bytes,a.id AS attempt_id,a.digest FROM scan_entry e
                    JOIN accepted_hash h ON h.entry_id=e.id AND h.scan_id=e.scan_id AND h.active
                    JOIN hash_attempt a ON a.id=h.attempt_id AND a.outcome='ACCEPTED'
                    WHERE e.scan_id=? AND e.sequence>? AND '''+HashPipeline.ELIGIBLE+' ORDER BY e.sequence LIMIT 500',c.scanId,Long.parseLong(p.after.toString()))
                for (Map row : rows) store.jdbc.update("INSERT INTO analysis_input(analysis_id,scan_id,entry_id,entry_sequence,attempt_id,algorithm,size_bytes,digest) VALUES (?,?,?,?,?,'SHA-256',?,?) ON CONFLICT DO NOTHING",revision,c.scanId,row.id,row.sequence,row.attempt_id,row.size_bytes,row.digest)
                save(c,rows.isEmpty() ? [revision:revision.toString(),stage:'GROUPS',size:'-1',digest:''] : [revision:revision.toString(),stage:'CAPTURE',after:rows.getLast().sequence.toString()])
            } else if (p.stage == 'GROUPS') {
                List<Map<String,Object>> rows = store.jdbc.queryForList('''SELECT size_bytes,digest,count(*) AS paths FROM analysis_input WHERE analysis_id=?
                    AND (size_bytes,digest)>(?,decode(?,'hex')) GROUP BY size_bytes,digest HAVING count(*)>=2 ORDER BY size_bytes,digest LIMIT 50''',revision,Long.parseLong(p.size.toString()),p.digest)
                for (Map row : rows) {
                    Map identity = identities(revision,((Number)row.size_bytes).longValue(),(byte[])row.digest)
                    store.jdbc.update('''INSERT INTO duplicate_group(id,analysis_id,scan_id,size_bytes,digest,path_count,object_count,identity_status)
                        VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING''',UUID.randomUUID(),revision,c.scanId,row.size_bytes,row.digest,row.paths,identity.objectCount,identity.status)
                }
                save(c,rows.isEmpty() ? [revision:revision.toString(),stage:'MEMBERS',after:'0'] :
                    [revision:revision.toString(),stage:'GROUPS',size:rows.getLast().size_bytes.toString(),digest:HexFormat.of().formatHex((byte[])rows.getLast().digest)])
            } else if (p.stage == 'MEMBERS') {
                List<Map<String,Object>> rows = store.jdbc.queryForList('''SELECT i.entry_id,i.entry_sequence,g.id AS group_id FROM analysis_input i
                    JOIN duplicate_group g ON g.analysis_id=i.analysis_id AND g.size_bytes=i.size_bytes AND g.digest=i.digest
                    WHERE i.analysis_id=? AND i.entry_sequence>? ORDER BY i.entry_sequence LIMIT 500''',revision,Long.parseLong(p.after.toString()))
                for (Map row : rows) store.jdbc.update('UPDATE analysis_input SET group_id=? WHERE analysis_id=? AND entry_id=?',row.group_id,revision,row.entry_id)
                save(c,[revision:revision.toString(),stage:rows.isEmpty() ? 'PUBLISH' : 'MEMBERS',after:rows.isEmpty() ? '0' : rows.getLast().entry_sequence.toString()])
            } else {
                // The scan row lock above serializes this compare-and-publish with all evidence changes.
                store.jdbc.update("UPDATE analysis_revision SET state='PUBLISHED',published_at=clock_timestamp() WHERE id=? AND state='BUILDING'",revision)
                store.jdbc.update('UPDATE scan SET current_analysis_id=? WHERE id=?',revision,c.scanId)
                store.event(c.jobId,'ANALYSIS_PUBLISHED','worker',[analysisId:revision,evidenceRevision:scan.evidence_revision.toString()])
                store.complete(c,null,null,'ANALYSIS_PUBLISHED')
            }
        }
    }
    private void save(WorkClaim c, Map p) {
        store.jdbc.update('UPDATE work_item SET payload=?::jsonb WHERE id=?',store.json(p),c.id)
        store.checkpoint(c)
    }
    private Map identities(UUID revision, long size, byte[] digest) {
        // Only known local filesystem identities within one mount/window qualify.
        // Repeated device/inode through distinct mounts is an alias warning, never an extra stored copy.
        Map row = store.one('''WITH members AS (
            SELECT e.* FROM analysis_input i JOIN scan_entry e ON e.id=i.entry_id
            WHERE i.analysis_id=? AND i.size_bytes=? AND i.digest=?
        ), objects AS (
            SELECT filesystem_type,device_major,device_minor,inode,count(*) AS paths,count(DISTINCT mount_id) AS mounts,
                min(link_count) AS links,count(DISTINCT (mode,mtime_seconds,mtime_nanos,ctime_seconds,ctime_nanos,link_count)) AS fingerprints,
                bool_and(filesystem_type IN ('ext2','ext3','ext4','xfs','btrfs','zfs','tmpfs','overlay') AND inode IS NOT NULL
                    AND mount_id IS NOT NULL AND device_major IS NOT NULL AND device_minor IS NOT NULL AND link_count IS NOT NULL) AS reliable
            FROM members GROUP BY filesystem_type,device_major,device_minor,inode
        ) SELECT count(*) AS objects,bool_and(coalesce(reliable,false) AND mounts=1 AND fingerprints=1 AND links>=paths) AS known,
            bool_or(mounts>1) AS aliases,bool_or(paths>1) AS hardlinks FROM objects''',revision,size,digest)
        [objectCount:row.known == Boolean.TRUE ? row.objects : null,
         status:row.aliases == Boolean.TRUE ? 'ALIASED_MOUNTS_UNCERTAIN' : row.known != Boolean.TRUE ? 'IDENTITY_UNKNOWN' : row.hardlinks == Boolean.TRUE ? 'HARD_LINKS_PRESENT' : 'DISTINCT_OBJECTS']
    }
}
