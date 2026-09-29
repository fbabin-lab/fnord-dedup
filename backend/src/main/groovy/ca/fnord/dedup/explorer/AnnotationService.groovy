package ca.fnord.dedup.explorer

import ca.fnord.dedup.inventory.*
import groovy.transform.CompileStatic
import org.springframework.stereotype.Service

@CompileStatic
@Service
class AnnotationService {
    static final List<String> STATES=['UNREVIEWED','REVIEWED','KEEP','REMOVAL_REVIEW'].asImmutable()
    final InventoryStore store
    final InventoryService inventory
    AnnotationService(InventoryStore store,InventoryService inventory) { this.store=store; this.inventory=inventory }
    void clock() { store.one('SELECT id FROM annotation_clock WHERE id=1 FOR UPDATE') }
    void tick() { store.jdbc.update('UPDATE annotation_clock SET revision=revision+1 WHERE id=1') }
    void audit(String actor,String action,String correlation,Map details) {
        store.jdbc.update('INSERT INTO audit_event(id,actor,action,correlation_id,details) VALUES (?,?,?,?,?::jsonb)',UUID.randomUUID(),actor,action,UUID.fromString(correlation),store.json(details))
    }
    Map summary(Map row,boolean full=false) {
        String memo=row.memo.toString()
        [locationId:row.location_id,observationId:row.id,version:row.annotation_version.toString(),memo:full ? memo : memo.substring(0,memo.offsetByCodePoints(0,Math.min(200,memo.codePointCount(0,memo.length())))),
         reviewState:row.review_state,tags:store.mapper.readValue(row.manual_tags.toString(),List),needsReview:row.annotations_need_review,
         baselineObservationId:row.baseline_entry_id,updatedAt:InventoryService.time(row.annotation_updated_at),updatedBy:row.annotation_updated_by]
    }
    Map observation(UUID location,UUID observation=null) {
        Map row=observation==null ? store.one('SELECT * FROM observation_search WHERE location_id=? ORDER BY sequence DESC LIMIT 1',location) :
            store.one('SELECT * FROM observation_search WHERE location_id=? AND id=?',location,observation)
        if (row==null) throw new JobProblem(404,'LOCATION_NOT_OBSERVED','This location has no matching observation.')
        row
    }
    Map get(UUID location,UUID observation=null) { summary(this.observation(location,observation),true) }
    void checkTags(List<UUID> tags) {
        for (UUID id : tags) if (store.one('SELECT id FROM tag WHERE id=?',id)==null) throw new JobProblem(422,'TAG_NOT_FOUND','A selected tag no longer exists.')
    }
    Map save(UUID location,Map body,String actor,String correlation) {
        Values.fields(body,['observationId','expectedVersion','memo','tagIds','reviewState'])
        UUID observed=Values.id(body.observationId)
        long version=Values.decimal(body.expectedVersion)
        String memo=Values.text(body.memo,20000), state=Values.choice(body.reviewState,STATES)
        List<UUID> tags=Values.ids(body.tagIds)
        store.tx.execute { tx ->
            clock()
            Map before=observation(location,observed)
            // Lock the scan after the annotation clock, matching query/selection lock order.
            store.one('SELECT id FROM scan WHERE id=? FOR SHARE',before.scan_id)
            before=observation(location,observed)
            if (((Number)before.annotation_version).longValue()!=version) throw new JobProblem(409,'ANNOTATION_CONFLICT','These notes changed in another view. Your draft is preserved; reload explicitly before retrying.')
            checkTags(tags)
            Map previous=summary(before,true)
            write(before,memo,state,tags,actor)
            tick()
            Map result=get(location,observed)
            audit(actor,'ANNOTATION_UPDATED',correlation,[locationId:location,before:previous,after:result])
            result
        }
    }
    void write(Map observation,String memo,String state,List<UUID> tags,String actor) {
        store.jdbc.update('''INSERT INTO file_annotation(location_id,memo,review_state,version,baseline_entry_id,baseline_attempt_id,updated_by)
            VALUES (?,?,?,1,?,?,?) ON CONFLICT(location_id) DO UPDATE SET memo=excluded.memo,review_state=excluded.review_state,
            version=file_annotation.version+1,baseline_entry_id=excluded.baseline_entry_id,baseline_attempt_id=excluded.baseline_attempt_id,
            updated_at=clock_timestamp(),updated_by=excluded.updated_by''',observation.location_id,memo,state,observation.id,
            observation.hash_status=='ACCEPTED' ? observation.accepted_attempt_id : null,actor)
        store.jdbc.update('DELETE FROM file_annotation_tag WHERE location_id=?',observation.location_id)
        if (!tags.isEmpty()) {
            List<Object> args=new ArrayList<>()
            for (UUID id : tags) { args.add(observation.location_id); args.add(id) }
            store.jdbc.update('INSERT INTO file_annotation_tag(location_id,tag_id) VALUES '+Collections.nCopies(tags.size(),'(?,?)').join(','),args.toArray())
        }
    }
    Map history(UUID location,String cursor,int limit) {
        String scope='history:'+location
        Map page=inventory.page(scope,cursor,limit,'SELECT coalesce(max(sequence),0) FROM scan_entry WHERE location_id=?',location)
        List<Map<String,Object>> rows=store.jdbc.queryForList('SELECT * FROM observation_search WHERE location_id=? AND sequence>? AND sequence<=? ORDER BY sequence LIMIT ?',location,page.after,page.cutoff,limit+1)
        boolean more=rows.size()>limit; if (more) rows.removeLast()
        [items:rows.collect { Map row -> InventoryService.observationRow(row)+[annotation:summary(row),hashStatus:row.hash_status] },nextCursor:more ? inventory.cursorFor(scope,page.cutoff,rows.getLast().sequence) : null]
    }
    Map tags(String query,String cursor,int limit) {
        String key=Values.lookup(Values.text(query ?: '',64))
        String scope='tags:'+Values.hash(key)
        Map page=inventory.page(scope,cursor,limit,'SELECT coalesce(max(sequence),0) FROM tag')
        List<Map<String,Object>> rows=store.jdbc.queryForList('SELECT * FROM tag WHERE sequence>? AND sequence<=? AND position(? in lookup_key)>0 ORDER BY sequence LIMIT ?',page.after,page.cutoff,key,limit+1)
        boolean more=rows.size()>limit; if (more) rows.removeLast()
        [items:rows.collect { Map row -> tag(row) },nextCursor:more ? inventory.cursorFor(scope,page.cutoff,rows.getLast().sequence) : null]
    }
    Map tag(Map row) { [id:row.id,label:row.label,version:row.version.toString()] }
    Map putTag(UUID id,Map body,String actor,String correlation) {
        Values.fields(body,id==null ? ['label'] : ['label','expectedVersion'])
        String label=Values.text(body.label,256).strip()
        Values.text(label,64,false)
        String key=Values.lookup(label)
        long expected=id==null ? 0L : Values.decimal(body.expectedVersion)
        store.tx.execute { tx ->
            clock()
            Map before=id==null ? null : store.one('SELECT * FROM tag WHERE id=?',id)
            if (id!=null && before==null) throw new JobProblem(404,'TAG_NOT_FOUND','The tag does not exist.')
            if (before!=null && ((Number)before.version).longValue()!=expected) throw new JobProblem(409,'TAG_CONFLICT','This tag changed. Reload the catalog before editing it.')
            Map duplicate=store.one('SELECT id FROM tag WHERE lookup_key=?',key)
            if (duplicate!=null && duplicate.id!=id) throw new JobProblem(409,'TAG_EXISTS','A tag with this normalized label already exists.')
            UUID resultId=id ?: UUID.randomUUID()
            if (id==null) store.jdbc.update('INSERT INTO tag(id,label,lookup_key) VALUES (?,?,?)',resultId,label,key)
            else store.jdbc.update('UPDATE tag SET label=?,lookup_key=?,version=version+1,updated_at=clock_timestamp() WHERE id=?',label,key,id)
            tick()
            Map result=tag(store.one('SELECT * FROM tag WHERE id=?',resultId))
            audit(actor,id==null ? 'TAG_CREATED' : 'TAG_RENAMED',correlation,[before:before==null ? null : tag(before),after:result])
            result
        }
    }
}
