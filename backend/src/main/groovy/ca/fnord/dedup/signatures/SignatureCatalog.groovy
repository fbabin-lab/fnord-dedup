package ca.fnord.dedup.signatures

import ca.fnord.dedup.explorer.*
import ca.fnord.dedup.inventory.*
import groovy.transform.CompileStatic
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets

/** Catalog writes never acquire a scheduler/job lock or open a source. */
@CompileStatic
@Service
class SignatureCatalog {
    final InventoryStore store
    final InventoryService inventory
    final AnnotationService annotations
    SignatureCatalog(InventoryStore store,InventoryService inventory,AnnotationService annotations) { this.store=store; this.inventory=inventory; this.annotations=annotations }
    long revision(boolean lock=false) { ((Number)store.one('SELECT revision FROM signature_clock WHERE id=1'+(lock ? ' FOR UPDATE' : '')).revision).longValue() }
    long advance() { ((Number)store.one('UPDATE signature_clock SET revision=revision+1 WHERE id=1 RETURNING revision').revision).longValue() }
    void requireRevision(long expected) { if (revision()!=expected) throw new JobProblem(409,'CATALOG_CONFLICT','The catalog changed. Refresh the catalog or stage the import again.') }
    static Map normalize(Map body) {
        Values.fields(body,['schemaVersion','id','revision','name','memo','tags','sizeBytes','algorithm','checksum','filename','filenameBytesBase64','filenameMatchMode','enabled','sourceNote'])
        if (body.containsKey('schemaVersion') && body.schemaVersion!=1) Values.invalid('Only schemaVersion 1 is supported.')
        String name=Values.text(body.name,200,false)
        if (name.isBlank()) Values.invalid('A nonblank signature name is required.')
        String digest=Values.text(body.checksum,64,false).toLowerCase(Locale.ROOT)
        if (!(digest ==~ '[0-9a-f]{64}')) Values.invalid('A complete 64-digit SHA-256 checksum is required.')
        String algorithm=Values.choice(body.algorithm,['SHA-256'])
        String filename=body.filename==null || body.filename=='' ? null : Values.text(body.filename,255,false)
        byte[] bytes=filename==null ? null : filename.getBytes(StandardCharsets.UTF_8)
        if (body.filenameBytesBase64!=null && body.filenameBytesBase64!='') {
            try {
                String raw=Values.text(body.filenameBytesBase64,340,false)
                bytes=Base64.decoder.decode(raw)
                if (Base64.encoder.encodeToString(bytes)!=raw) Values.invalid('Filename base64 must be canonical.')
            } catch (IllegalArgumentException ignored) { Values.invalid('Invalid filename base64.') }
        }
        if (bytes!=null && (bytes.length==0 || bytes.length>255 || contains(bytes,(byte)0) || contains(bytes,(byte)47) || Arrays.equals(bytes,'.'.bytes) || Arrays.equals(bytes,'..'.bytes)))
            Values.invalid('Use one raw basename of 1 to 255 bytes without slash or NUL.')
        // When both are supplied, raw bytes are authoritative. Display names may be lossy for non-UTF-8 observations.
        String mode=Values.choice(body.getOrDefault('filenameMatchMode','ADVISORY'),['ADVISORY','REQUIRED_EXACT'])
        if (mode=='REQUIRED_EXACT' && bytes==null) Values.invalid('Required-exact mode needs filename bytes; renamed copies will not match.')
        if (!(body.getOrDefault('enabled',true) instanceof Boolean)) Values.invalid('Enabled must be a boolean.')
        Object rawTags=body.getOrDefault('tags',[])
        if (!(rawTags instanceof List) || ((List)rawTags).size()>100) Values.invalid('Use at most 100 tag labels.')
        List<String> tags=new ArrayList<>(); Set<String> seen=new HashSet<>()
        for (Object raw : (List)rawTags) {
            String tag=Values.text(raw,256).strip(); Values.text(tag,64,false)
            if (!seen.add(Values.lookup(tag))) Values.invalid('Tag labels must be distinct after normalization.')
            tags.add(tag)
        }
        [name:name,memo:Values.text(body.getOrDefault('memo',''),20000),tags:tags,sizeBytes:Long.toString(Values.decimal(body.sizeBytes)),algorithm:algorithm,checksum:digest,
         filename:filename,filenameBytesBase64:bytes==null ? null : Base64.encoder.encodeToString(bytes),filenameMatchMode:mode,
         enabled:body.getOrDefault('enabled',true),sourceNote:Values.text(body.getOrDefault('sourceNote',''),2000)]
    }
    private static boolean contains(byte[] bytes,byte value) { for (byte b : bytes) if (b==value) return true; false }
    Map write(UUID id,Map normalized,long expected,long catalogRevision,String origin,UUID observation=null,UUID attempt=null) {
        Map old=store.one('SELECT * FROM signature WHERE id=?',id)
        if ((old==null ? 0L : ((Number)old.revision).longValue())!=expected) throw new JobProblem(409,'SIGNATURE_CONFLICT','This signature changed or the identifier already exists. Reload before editing.')
        long next=expected+1L
        if (old==null) store.jdbc.update('INSERT INTO signature(id,revision) VALUES (?,?)',id,next)
        else store.jdbc.update('UPDATE signature SET revision=? WHERE id=?',next,id)
        List<Map> tags=new ArrayList<>()
        for (String label : (List<String>)normalized.tags) {
            String key=Values.lookup(label)
            Map tag=store.one('SELECT * FROM tag WHERE lookup_key=?',key)
            if (tag==null) {
                UUID tagId=UUID.randomUUID()
                store.jdbc.update('INSERT INTO tag(id,label,lookup_key) VALUES (?,?,?)',tagId,label,key)
                annotations.tick()
                tag=store.one('SELECT * FROM tag WHERE id=?',tagId)
            }
            tags.add(annotations.tag(tag))
        }
        store.jdbc.update('''INSERT INTO signature_revision(signature_id,revision,catalog_revision,name,memo,size_bytes,algorithm,digest,
            filename,filename_bytes,filename_match_mode,enabled,origin,source_note,observation_id,attempt_id,tags)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)''',id,next,catalogRevision,normalized.name,normalized.memo,Long.parseLong(normalized.sizeBytes.toString()),normalized.algorithm,
            HexFormat.of().parseHex(normalized.checksum.toString()),normalized.filename,normalized.filenameBytesBase64==null ? null : Base64.decoder.decode(normalized.filenameBytesBase64.toString()),
            normalized.filenameMatchMode,normalized.enabled,origin,normalized.sourceNote,observation,attempt,store.json(tags))
        for (Map tag : tags) store.jdbc.update('INSERT INTO signature_tag(signature_id,revision,tag_id) VALUES (?,?,?)',id,next,tag.id)
        get(id,next)
    }
    Map put(UUID id,Map body,String actor,String correlation) {
        Map raw=new LinkedHashMap(body)
        Object expectedRaw=raw.remove('expectedRevision')
        UUID observation=raw.containsKey('observationId') ? Values.id(raw.remove('observationId')) : null
        if (observation!=null && id!=null) Values.invalid('Create-from-observation is only available for a new signature.')
        Values.fields(raw,['name','memo','tags','tagIds','sizeBytes','algorithm','checksum','filename','filenameBytesBase64','filenameMatchMode','enabled','sourceNote'])
        long expected=id==null ? 0L : Values.decimal(expectedRaw)
        store.tx.execute { tx ->
            annotations.clock()
            revision(true)
            if (raw.containsKey('tagIds')) {
                if (raw.containsKey('tags')) Values.invalid('Provide tagIds or tag labels, not both.')
                List<UUID> ids=Values.ids(raw.remove('tagIds'))
                annotations.checkTags(ids)
                raw.put('tags',ids.collect { UUID tagId -> store.one('SELECT label FROM tag WHERE id=?',tagId).label })
            }
            Map observed=null
            if (observation!=null) {
                observed=store.one('SELECT * FROM observation_search WHERE id=?',observation)
                if (observed==null) throw new JobProblem(404,'OBSERVATION_NOT_FOUND','The observation does not exist.')
                store.one('SELECT id FROM scan WHERE id=? FOR SHARE',observed.scan_id)
                observed=store.one('SELECT * FROM observation_search WHERE id=?',observation)
                if (observed.hash_status!='ACCEPTED') throw new JobProblem(409,'HASH_REQUIRED','Explicitly calculate SHA-256, wait for accepted evidence, then continue creating the signature.')
                // Caller cannot substitute any fingerprint or basename when creating from an observation.
                if (raw.keySet().any { Object k -> k in ['sizeBytes','algorithm','checksum','filename','filenameBytesBase64'] }) Values.invalid('Observation-derived fingerprint and filename fields must be omitted.')
                raw.putAll([sizeBytes:observed.size_bytes.toString(),algorithm:'SHA-256',checksum:HexFormat.of().formatHex((byte[])observed.digest),filename:observed.display_name,
                    filenameBytesBase64:Base64.encoder.encodeToString((byte[])observed.name_bytes)])
            }
            Map normalized=normalize(raw)
            long catalog=advance()
            Map result=write(id ?: UUID.randomUUID(),normalized,expected,catalog,observation==null ? 'MANUAL' : 'FROM_OBSERVATION',observation,(UUID)observed?.accepted_attempt_id)
            annotations.audit(actor,id==null ? 'SIGNATURE_CREATED' : 'SIGNATURE_UPDATED',correlation,[signatureId:result.id,revision:result.revision,catalogRevision:Long.toString(catalog),observationId:observation])
            result
        }
    }
    Map dto(Map row) {
        List tags=(List)store.mapper.readValue(row.tags.toString(),List)
        [id:row.signature_id,revision:row.revision.toString(),catalogRevision:row.catalog_revision.toString(),name:row.name,memo:row.memo,tags:tags,
         sizeBytes:row.size_bytes.toString(),algorithm:row.algorithm,checksum:HexFormat.of().formatHex((byte[])row.digest),filename:row.filename,
         filenameBytesBase64:row.filename_bytes==null ? null : Base64.encoder.encodeToString((byte[])row.filename_bytes),filenameMatchMode:row.filename_match_mode,
         enabled:row.enabled,origin:row.origin,sourceNote:row.source_note,observationId:row.observation_id,attemptId:row.attempt_id,
         createdAt:InventoryService.time(row.created_at),updatedAt:InventoryService.time(row.updated_at)]
    }
    Map get(UUID id,Long revision=null) {
        Map row=store.one('''SELECT r.*,s.created_at FROM signature s JOIN signature_revision r ON r.signature_id=s.id
            AND r.revision=coalesce(?,s.revision) WHERE s.id=?''',revision,id)
        if (row==null) throw new JobProblem(404,'SIGNATURE_NOT_FOUND','This signature revision does not exist.')
        dto(row)
    }
    Map list(String query,String catalog,String cursor,int limit) {
        long rev=catalog==null ? revision() : Values.decimal(catalog)
        if (rev>revision()) Values.invalid('The catalog revision does not exist.')
        String text=Values.text(query ?: '',200)
        String scope='signatures:'+rev+':'+Values.hash(text)
        Map page=inventory.page(scope,cursor,limit,'SELECT coalesce(max(sequence),0) FROM signature')
        List<Map<String,Object>> rows=store.jdbc.queryForList('''SELECT r.*,s.sequence,s.created_at FROM signatures_at(?) r JOIN signature s ON s.id=r.signature_id
            WHERE s.sequence>? AND s.sequence<=? AND (position(lower(?) in lower(r.name))>0 OR position(lower(?) in lower(r.memo))>0)
            ORDER BY s.sequence LIMIT ?''',rev,page.after,page.cutoff,text,text,limit+1)
        boolean more=rows.size()>limit; if (more) rows.removeLast()
        [catalogRevision:Long.toString(rev),items:rows.collect { Map row -> dto(row) },nextCursor:more ? inventory.cursorFor(scope,page.cutoff,rows.getLast().sequence) : null]
    }
}
