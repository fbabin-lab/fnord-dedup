package ca.fnord.dedup.inventory

import ca.fnord.dedup.signatures.SignaturePipeline
import ca.fnord.dedup.roots.*
import groovy.transform.CompileStatic
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@CompileStatic
@Service
class InventoryService {
    final InventoryStore store
    final SourceRegistry sources
    InventoryService(InventoryStore store, SourceRegistry sources) { this.store = store; this.sources = sources }

    Map<String,Object> create(Map<String,Object> body, String key, String actor, String correlationId) {
        Set<String> allowed = Set.of('name','sourceIds','hashAlgorithm','includeSignatureCandidates','textIndexingEnabled')
        if (!allowed.containsAll(body.keySet())) throw new JobProblem(422,'UNSUPPORTED_OPTION','The request contains an unsupported scan option.')
        if (!(body.name instanceof String) || ((String)body.name).isBlank() || ((String)body.name).length() > 200)
            throw new JobProblem(422,'INVALID_NAME','Enter a scan name of 1 to 200 characters.')
        if (body.getOrDefault('hashAlgorithm','SHA-256') != 'SHA-256' || !(body.getOrDefault('includeSignatureCandidates',false) instanceof Boolean) || body.getOrDefault('textIndexingEnabled',false) != Boolean.FALSE)
            throw new JobProblem(422,'UNSUPPORTED_OPTION','Only SHA-256 is supported. Text indexing is not available yet.')
        if (!(body.sourceIds instanceof List) || ((List)body.sourceIds).isEmpty() || ((List)body.sourceIds).size() > 100)
            throw new JobProblem(422,'INVALID_SOURCES','Select between 1 and 100 registered sources.')
        List<UUID> selected = new ArrayList<>()
        for (Object raw : (List)body.sourceIds) {
            try { selected.add(UUID.fromString((String)raw)) }
            catch (IllegalArgumentException | ClassCastException | NullPointerException ignored) { throw new JobProblem(422,'INVALID_SOURCES','Source IDs must be UUIDs.') }
        }
        if (new HashSet<UUID>(selected).size() != selected.size()) throw new JobProblem(422,'DUPLICATE_SOURCE','Select each source once.')
        selected.sort { UUID a, UUID b -> a.compareTo(b) }
        String requestKey = key == null ? UUID.randomUUID().toString() : key
        if (!(requestKey ==~ '[!-~]{1,128}')) throw new JobProblem(422,'INVALID_IDEMPOTENCY_KEY','Use 1 to 128 printable ASCII characters for Idempotency-Key.')
        Map<String,Object> normalized = [name:body.name,sourceIds:selected,hashAlgorithm:'SHA-256',includeSignatureCandidates:body.getOrDefault('includeSignatureCandidates',false),textIndexingEnabled:false] as Map<String,Object>
        String payloadHash = HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(store.json(normalized).getBytes(StandardCharsets.UTF_8)))
        store.tx.execute { status ->
            store.one('SELECT id FROM job_admission WHERE id=1 FOR UPDATE')
            Map previous = store.one('SELECT payload_hash,response::text AS response FROM idempotency_record WHERE actor=? AND endpoint=? AND request_key=?',actor,'/scans',requestKey)
            if (previous != null) {
                if (previous.payload_hash != payloadHash) throw new JobProblem(409,'IDEMPOTENCY_CONFLICT','This key was already used for a different scan request.')
                return store.parse((String)previous.response)
            }
            if (store.jdbc.queryForObject("SELECT count(*) FROM job WHERE state NOT IN ('COMPLETED','COMPLETED_WITH_ERRORS','CANCELLED','FAILED')",Long) >= 10L ||
                store.jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE actor=? AND endpoint IN ('/scans','/hash-jobs','/signature-check-jobs') AND created_at>clock_timestamp()-interval '1 minute'",Long,actor) >= 5L)
                throw new JobProblem(429,'SCAN_CAPACITY','The scan queue or creation rate limit has been reached. Finish or cancel existing work, or retry later.')
            List<SourceDefinition> definitions = new ArrayList<>()
            for (UUID id : selected) {
                SourceDefinition source = sources.definition(id)
                SourceView view = sources.list().find { SourceView s -> s.id == id }
                if (source == null || !source.enabled) throw new JobProblem(422,'INVALID_SOURCE','A selected source is unknown or disabled.')
                if (view != null && view.status in ['SOURCE_OVERLAP','APPLICATION_STORAGE_OVERLAP','WRITABLE_SOURCE','UNSUPPORTED_PLATFORM','NATIVE_LINK_ERROR'])
                    throw new JobProblem(422,view.status,'A selected source failed the source-safety checks.')
                definitions.add(source)
            }
            UUID scanId = UUID.randomUUID(), jobId = UUID.randomUUID()
            store.jdbc.update('INSERT INTO scan(id,name,configuration_revision,options,signature_catalog_revision) VALUES (?,?,?,?::jsonb,?)',scanId,body.name,sources.revision,store.json([hashAlgorithm:'SHA-256',inventoryOnly:false,includeSignatureCandidates:body.getOrDefault('includeSignatureCandidates',false),textIndexingEnabled:false]),SignaturePipeline.current(store))
            store.jdbc.update("INSERT INTO job(id,scan_id,type,phase,state) VALUES (?,?,'SCAN','INVENTORY','QUEUED')",jobId,scanId)
            for (SourceDefinition source : definitions) {
                String snapshot = store.json(source)
                store.jdbc.update('''INSERT INTO source_root(id,source_instance_id,configuration_revision,snapshot) VALUES (?,?,?,?::jsonb)
                    ON CONFLICT (id,source_instance_id) DO UPDATE SET configuration_revision=excluded.configuration_revision,snapshot=excluded.snapshot''',source.id,source.sourceInstanceId,sources.revision,snapshot)
                UUID location = store.location(source.id,source.sourceInstanceId,null,new byte[0],new byte[0])
                store.jdbc.update('INSERT INTO scan_source(scan_id,source_id,source_instance_id,root_location_id,snapshot) VALUES (?,?,?,?,?::jsonb)',scanId,source.id,source.sourceInstanceId,location,snapshot)
                store.addWork(jobId,location)
            }
            store.event(jobId,'QUEUED',actor,[includeSignatureCandidates:body.getOrDefault('includeSignatureCandidates',false),catalogRevision:store.one('SELECT signature_catalog_revision FROM scan WHERE id=?',scanId).signature_catalog_revision.toString()])
            store.jdbc.update('INSERT INTO audit_event(id,actor,action,correlation_id,details) VALUES (?,?,?,?,?::jsonb)',UUID.randomUUID(),actor,'SCAN_CREATED',UUID.fromString(correlationId),store.json([scanId:scanId,jobId:jobId,includeSignatureCandidates:body.getOrDefault('includeSignatureCandidates',false),catalogRevision:store.one('SELECT signature_catalog_revision FROM scan WHERE id=?',scanId).signature_catalog_revision.toString()]))
            Map<String,Object> response = [scanId:scanId.toString(),jobId:jobId.toString(),inventoryOnly:false] as Map<String,Object>
            store.jdbc.update('INSERT INTO idempotency_record(actor,endpoint,request_key,payload_hash,response) VALUES (?,?,?,?,?::jsonb)',actor,'/scans',requestKey,payloadHash,store.json(response))
            response
        }
    }

    void validateResume(UUID scanId) {
        Map scan = store.one('SELECT configuration_revision FROM scan WHERE id=?',scanId)
        if (scan.configuration_revision != sources.revision) throw new JobProblem(409,'SOURCE_CONFIGURATION_CHANGED','Source configuration changed. Restore the captured configuration or start a new scan.')
        sources.refresh()
        for (Map row : store.jdbc.queryForList('SELECT source_id,root_identity::text AS root_identity FROM scan_source WHERE scan_id=?',scanId)) {
            UUID sourceId = (UUID)row.source_id
            SourceView view = sources.list().find { SourceView v -> v.id == sourceId }
            if (view == null || view.status != 'AVAILABLE') throw new JobProblem(409,view?.status ?: 'SOURCE_CONFIGURATION_CHANGED','A selected source cannot be safely resumed. Check the source status and mounts.')
            if (row.root_identity != null) {
                String fingerprint = store.json(InventoryEntry.identity(sources.identity(sourceId)))
                if (store.one('SELECT root_identity=?::jsonb AS matches FROM scan_source WHERE scan_id=? AND source_id=?',fingerprint,scanId,sourceId).matches != Boolean.TRUE)
                    throw new JobProblem(409,'SOURCE_CONFIGURATION_CHANGED','The mounted root identity changed. Start a new scan for a replacement dataset.')
            }
        }
    }

    Map<String,Object> control(UUID id, String action, String actor, String correlationId) {
        Long validatedVersion = null
        if (action == 'resume') {
            Map before = store.job(id)
            if (!(before.state in ['PAUSED','INTERRUPTED'])) throw new JobProblem(409,'INVALID_JOB_STATE','Only paused or interrupted jobs can resume.')
            validatedVersion = ((Number)before.version).longValue()
            try { if (before.type!='SIGNATURE_MATCH') validateResume((UUID)before.scan_id) }
            catch (JobProblem problem) {
                if (before.type != 'INVENTORY' && problem.code == 'SOURCE_CONFIGURATION_CHANGED') store.invalidateScanEvidence((UUID)before.scan_id,problem.code)
                store.jdbc.update('UPDATE job SET block_code=? WHERE id=? AND version=?',problem.code,id,validatedVersion)
                throw problem
            }
        }
        final Long expected = validatedVersion
        store.tx.executeWithoutResult { status ->
            Map row = store.job(id,true)
            String state = (String)row.state, next = state
            switch (action) {
                case 'pause':
                    if (state == 'QUEUED') next = 'PAUSED'
                    else if (state == 'RUNNING') next = 'PAUSE_REQUESTED'
                    else if (!(state in ['PAUSED','PAUSE_REQUESTED','CANCEL_REQUESTED','CANCELLED'])) conflict()
                    break
                case 'cancel':
                    if (state in ['QUEUED','PAUSED','INTERRUPTED']) next = 'CANCELLED'
                    else if (state in ['RUNNING','PAUSE_REQUESTED']) next = 'CANCEL_REQUESTED'
                    else if (!(state in ['CANCEL_REQUESTED','CANCELLED'])) conflict()
                    break
                case 'resume':
                    if (!(state in ['PAUSED','INTERRUPTED']) || ((Number)row.version).longValue() != expected) conflict()
                    next = 'QUEUED'
                    break
                default: throw new JobProblem(404,'ACTION_NOT_FOUND','Unknown job action.')
            }
            if (next != state) {
                store.state(id,next,actor)
                store.jdbc.update('INSERT INTO audit_event(id,actor,action,correlation_id,details) VALUES (?,?,?,?,?::jsonb)',UUID.randomUUID(),actor,'JOB_'+action.toUpperCase(Locale.ROOT),UUID.fromString(correlationId),store.json([jobId:id,state:next]))
            }
        }
        job(id)
    }
    private static void conflict() { throw new JobProblem(409,'INVALID_JOB_STATE','This action is incompatible with the current job state.') }

    Map<String,Object> job(UUID id) {
        Map row = store.job(id)
        Map active = row.current_location_id == null ? null : store.one('SELECT display_path FROM file_location WHERE id=?',row.current_location_id)
        [id:row.id,scanId:row.scan_id,type:row.type,catalogRevision:row.signature_catalog_revision?.toString(),state:row.state,phase:row.phase,version:row.version.toString(),
         createdAt:time(row.created_at),startedAt:time(row.started_at),updatedAt:time(row.updated_at),finishedAt:time(row.finished_at),
         heartbeatAt:time(row.heartbeat_at),checkpointAt:time(row.checkpoint_at),currentSourceId:row.current_source_id,
         currentPath:active?.display_path,blockCode:row.block_code,leaseSeconds:row.lease_seconds,heartbeatSeconds:row.heartbeat_seconds,
         discoveredEntries:row.discovered_entries.toString(),discoveredFiles:row.discovered_files.toString(),discoveredDirectories:row.discovered_directories.toString(),
         discoveredBytes:row.discovered_bytes.toString(),errorCount:row.error_count.toString(),skippedEntries:row.skipped_entries.toString(),
         pendingWork:row.pending_work.toString(),completedWork:row.completed_work.toString(),totalKnown:row.phase != 'INVENTORY' && row.phase != 'CANDIDATE_SELECTION',
         candidateFiles:row.candidate_files.toString(),candidateBytes:row.candidate_bytes.toString(),hashedFiles:row.hashed_files.toString(),reusedFiles:row.reused_files.toString(),
         physicalBytesRead:row.physical_bytes_read.toString(),usefulBytesHashed:row.useful_bytes_hashed.toString(),
         waitingForIo:row.state in ['RUNNING','PAUSE_REQUESTED','CANCEL_REQUESTED'] && row.current_location_id != null &&
            ((java.util.Date)(row.checkpoint_at ?: row.started_at)).time < System.currentTimeMillis()-5000L] as Map<String,Object>
    }
    Map<String,Object> scan(UUID id) {
        Map row = store.one("SELECT s.*,j.id AS job_id FROM scan s JOIN job j ON j.scan_id=s.id AND j.type IN ('INVENTORY','SCAN') WHERE s.id=?",id)
        if (row == null) throw new JobProblem(404,'SCAN_NOT_FOUND','The scan does not exist.')
        List<Map<String,Object>> roots = new ArrayList<>()
        for (Map source : store.jdbc.queryForList('SELECT * FROM scan_source WHERE scan_id=? ORDER BY source_id',id)) {
            Map snapshot = store.parse(source.snapshot.toString())
            roots.add([sourceId:source.source_id,sourceInstanceId:source.source_instance_id,label:snapshot.label,
                rootLocationId:source.root_location_id,coverage:source.coverage] as Map<String,Object>)
        }
        [id:row.id,name:row.name,configurationRevision:row.configuration_revision,createdAt:time(row.created_at),
         inventoryFrozenAt:time(row.inventory_frozen_at),inventoryOnly:store.parse(row.options.toString()).inventoryOnly,analysisAvailable:row.current_analysis_id != null,
         includeSignatureCandidates:store.parse(row.options.toString()).includeSignatureCandidates,signatureCatalogRevision:row.signature_catalog_revision.toString(),signatureRunId:row.current_signature_run_id,
         analysisId:row.current_analysis_id,evidenceRevision:row.evidence_revision.toString(),
         activeHashJobs:store.jdbc.queryForList("SELECT id FROM job WHERE scan_id=? AND type IN ('HASH','SIGNATURE_CHECK','SIGNATURE_MATCH') AND state NOT IN ('COMPLETED','COMPLETED_WITH_ERRORS','CANCELLED','FAILED') ORDER BY sequence LIMIT 10",id).collect { Map item -> job((UUID)item.id) },
         latestJob:job((UUID)store.one('SELECT id FROM job WHERE scan_id=? ORDER BY sequence DESC LIMIT 1',id).id),
         job:job((UUID)row.job_id),sources:roots] as Map<String,Object>
    }
    Map<String,Object> scans(String cursor, int limit) { list('scans',cursor,limit) }
    Map<String,Object> jobs(String cursor, int limit) { list('jobs',cursor,limit) }
    private Map<String,Object> list(String kind, String cursor, int limit) {
        String table = kind == 'scans' ? 'scan' : 'job'
        Map page = page(kind,cursor,limit,"SELECT coalesce(max(sequence),0) FROM ${table}".toString())
        List<Map<String,Object>> rows = store.jdbc.queryForList("SELECT id,sequence FROM ${table} WHERE sequence>? AND sequence<=? ORDER BY sequence LIMIT ?".toString(),page.after,page.cutoff,limit+1)
        boolean more = rows.size() > limit
        if (more) rows.removeLast()
        List<Map<String,Object>> items = new ArrayList<>()
        for (Map row : rows) items.add(kind == 'scans' ? scan((UUID)row.id) : job((UUID)row.id))
        [items:items,nextCursor:more ? cursorFor(kind,page.cutoff,rows.getLast().sequence) : null] as Map<String,Object>
    }
    Map<String,Object> errors(UUID jobId, String cursor, int limit) {
        store.job(jobId)
        String scope = 'errors:'+jobId
        Map page = page(scope,cursor,limit,'SELECT coalesce(max(id),0) FROM job_error WHERE job_id=?',jobId)
        List<Map<String,Object>> rows = store.jdbc.queryForList('SELECT e.*,l.display_path FROM job_error e JOIN file_location l ON l.id=e.location_id WHERE e.job_id=? AND e.id>? AND e.id<=? ORDER BY e.id LIMIT ?',jobId,page.after,page.cutoff,limit+1)
        boolean more = rows.size() > limit
        if (more) rows.removeLast()
        List<Map<String,Object>> items = new ArrayList<>()
        for (Map row : rows) items.add([id:row.id.toString(),locationId:row.location_id,displayPath:row.display_path,code:row.code,detail:row.detail,createdAt:time(row.created_at)] as Map<String,Object>)
        [items:items,nextCursor:more ? cursorFor(scope,page.cutoff,rows.getLast().id) : null] as Map<String,Object>
    }
    Map<String,Object> children(UUID scanId, UUID parentId, String cursor, int limit) {
        Map parent = store.one('SELECT e.entry_type,l.parent_id,l.display_path FROM scan_entry e JOIN file_location l ON l.id=e.location_id WHERE e.scan_id=? AND e.location_id=?',scanId,parentId)
        if (parent == null) throw new JobProblem(404,'DIRECTORY_NOT_OBSERVED','This directory has no committed observation in the selected scan yet.')
        if (parent.entry_type != 'DIRECTORY') throw new JobProblem(422,'NOT_A_DIRECTORY','Select an observed directory.')
        String scope = 'children:'+scanId+':'+parentId
        Map page = page(scope,cursor,limit,'SELECT coalesce(max(sequence),0) FROM scan_entry WHERE scan_id=?',scanId)
        List<Map<String,Object>> rows = store.jdbc.queryForList('''SELECT e.*,l.parent_id,l.display_name,l.display_path,l.name_bytes,l.relative_path_bytes,l.extension,
            EXISTS(SELECT 1 FROM observation_validation v WHERE v.entry_id=e.id) AS unstable
            FROM scan_entry e JOIN file_location l ON l.id=e.location_id WHERE e.scan_id=? AND l.parent_id=?
            AND e.sequence>? AND e.sequence<=? ORDER BY e.sequence LIMIT ?''',scanId,parentId,page.after,page.cutoff,limit+1)
        boolean more = rows.size() > limit
        if (more) rows.removeLast()
        List<Map<String,Object>> items = new ArrayList<>()
        for (Map row : rows) items.add(observationRow(row))
        [items:items,nextCursor:more ? cursorFor(scope,page.cutoff,rows.getLast().sequence) : null,parentLocationId:parent.parent_id,displayPath:parent.display_path] as Map<String,Object>
    }
    Map<String,Object> observation(UUID id) {
        Map row = store.one('''SELECT e.*,l.parent_id,l.display_name,l.display_path,l.name_bytes,l.relative_path_bytes,l.extension,
            EXISTS(SELECT 1 FROM observation_validation v WHERE v.entry_id=e.id) AS unstable
            FROM scan_entry e JOIN file_location l ON l.id=e.location_id WHERE e.id=?''',id)
        if (row == null) throw new JobProblem(404,'OBSERVATION_NOT_FOUND','The observation does not exist.')
        observationRow(row) + ([hash:HashService.evidence(store,id)] as Map<String,Object>)
    }
    static Map<String,Object> observationRow(Map row) {
        [id:row.id,scanId:row.scan_id,locationId:row.location_id,parentLocationId:row.parent_id,sourceId:row.source_id,sourceInstanceId:row.source_instance_id,
         name:row.display_name,path:row.display_path,relativePathBytesBase64:Base64.encoder.encodeToString((byte[])row.relative_path_bytes),
         nameBytesBase64:Base64.encoder.encodeToString((byte[])row.name_bytes),extension:row.extension,entryType:row.entry_type,sizeBytes:row.size_bytes?.toString(),
         metadataMask:row.metadata_mask,mode:row.mode,inode:row.inode?.toString(),mountId:row.mount_id?.toString(),deviceMajor:row.device_major?.toString(),deviceMinor:row.device_minor?.toString(),
         mtimeSeconds:row.mtime_seconds?.toString(),mtimeNanos:row.mtime_nanos,ctimeSeconds:row.ctime_seconds?.toString(),ctimeNanos:row.ctime_nanos,
         birthSeconds:row.birth_seconds?.toString(),birthNanos:row.birth_nanos,mtime:time(row.mtime),ctime:time(row.ctime),
         linkCount:row.link_count?.toString(),blocks:row.blocks?.toString(),uid:row.uid?.toString(),gid:row.gid?.toString(),filesystemType:row.filesystem_type,
         symlinkTargetBytesBase64:row.symlink_target == null ? null : Base64.encoder.encodeToString((byte[])row.symlink_target),
         discoveryStatus:row.discovery_status,directoryCoverage:row.directory_coverage,unstable:row.unstable,observedAt:time(row.observed_at)] as Map<String,Object>
    }
    Map<String,Object> page(String scope, String cursor, int limit, String cutoffSql, Object... args) {
        if (limit < 1 || limit > 500) throw new JobProblem(422,'INVALID_PAGE_SIZE','Page size must be between 1 and 500.')
        if (cursor == null || cursor.isEmpty()) return [after:0L,cutoff:store.jdbc.queryForObject(cutoffSql,Long,args)] as Map<String,Object>
        try {
            if (cursor.length() > 1024) throw new IllegalArgumentException()
            Map data = store.parse(new String(Base64.urlDecoder.decode(cursor),StandardCharsets.UTF_8))
            if (data.scope != scope) throw new JobProblem(409,'CURSOR_STALE','This cursor belongs to a different view. Refresh the list.')
            long after = Long.parseLong(data.after.toString()), cutoff = Long.parseLong(data.cutoff.toString())
            if (after < 0L || cutoff < after) throw new IllegalArgumentException()
            return [after:after,cutoff:cutoff] as Map<String,Object>
        } catch (JobProblem e) { throw e }
        catch (Exception ignored) { throw new JobProblem(422,'INVALID_CURSOR','The page cursor is invalid.') }
    }
    String cursorFor(String scope, Object cutoff, Object after) {
        Base64.urlEncoder.withoutPadding().encodeToString(store.json([scope:scope,cutoff:cutoff.toString(),after:after.toString()]).getBytes(StandardCharsets.UTF_8))
    }
    static String time(Object value) { value == null ? null : ((java.sql.Timestamp)value).toInstant().toString() }
}
