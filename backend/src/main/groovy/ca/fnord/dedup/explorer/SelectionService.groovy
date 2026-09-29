package ca.fnord.dedup.explorer

import ca.fnord.dedup.inventory.*
import groovy.transform.CompileStatic
import org.springframework.stereotype.Service

@CompileStatic
@Service
class SelectionService {
    final InventoryStore store
    final SearchService search
    final AnnotationService annotations
    SelectionService(InventoryStore store,SearchService search,AnnotationService annotations) { this.store=store; this.search=search; this.annotations=annotations }
    Map freeze(UUID scanId,Map body,String actor,String correlation) {
        Values.fields(body,['query','viewToken','observationIds'])
        if (!(body.query instanceof Map)) Values.invalid('Provide the query being reviewed.')
        Map query=search.normalize((Map)body.query)
        String view=Values.text(body.viewToken,2048,false)
        List<UUID> ids=body.containsKey('observationIds') ? Values.ids(body.observationIds,500,false) : null
        store.tx.execute { status ->
            Map context=search.capture(scanId,query,view); context.remove('after')
            List<Map<String,Object>> rows=search.rows(scanId,query,context,501,ids)
            search.unchanged(context)
            if (rows.isEmpty()) throw new JobProblem(422,'EMPTY_SELECTION','No observations match this selection.')
            if (rows.size()>500) throw new JobProblem(422,'SELECTION_TOO_LARGE','A bulk selection is limited to 500 observations. Refine the filters or select individual rows.')
            if (ids!=null && rows.size()!=ids.size()) throw new JobProblem(409,'SELECTION_MISMATCH','Selected rows must belong to the captured query result.')
            UUID id=UUID.randomUUID()
            BigDecimal bytes=BigDecimal.ZERO
            for (Map row : rows) if (row.size_bytes!=null) bytes=bytes.add(new BigDecimal(row.size_bytes.toString()))
            store.jdbc.update('''INSERT INTO selection(id,actor,scan_id,analysis_id,evidence_revision,query_revision,annotation_revision,entry_cutoff,query_snapshot,selected_count,selected_bytes,unknown_sizes,signature_catalog_revision,signature_run_id)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?)''',id,actor,scanId,context.analysis==null ? null : Values.id(context.analysis),Long.parseLong(context.evidence.toString()),
                Long.parseLong(context.revision.toString()),Long.parseLong(context.annotations.toString()),Long.parseLong(context.cutoff.toString()),store.json(query),rows.size(),bytes,rows.count { Map row -> row.size_bytes==null },Long.parseLong(context.catalog.toString()),context.signatures==null ? null : Values.id(context.signatures))
            int ordinal=0
            for (Map row : rows) store.jdbc.update('''INSERT INTO selection_member(selection_id,scan_id,entry_id,location_id,ordinal,annotation_version,annotation_snapshot,accepted_attempt_id,was_stale)
                VALUES (?,?,?,?,?,?,?::jsonb,?,?)''',id,scanId,row.id,row.location_id,++ordinal,row.annotation_version,store.json(annotations.summary(row,true)),row.accepted_attempt_id,row.stale)
            annotations.audit(actor,'SELECTION_FROZEN',correlation,[selectionId:id,scanId:scanId,count:rows.size().toString(),query:query])
            describe(owned(id,actor))
        }
    }
    Map owned(UUID id,String actor) {
        Map row=store.one('SELECT *,expires_at<=clock_timestamp() AS expired FROM selection WHERE id=? AND actor=?',id,actor)
        if (row==null) throw new JobProblem(404,'SELECTION_NOT_FOUND','The selection does not exist for this operator.')
        row
    }
    Map describe(Map selection) {
        [id:selection.id,scanId:selection.scan_id,analysisId:selection.analysis_id,count:selection.selected_count.toString(),totalBytes:selection.selected_bytes.toString(),unknownSizes:selection.unknown_sizes.toString(),
         signatureCatalogRevision:selection.signature_catalog_revision.toString(),signatureRunId:selection.signature_run_id,createdAt:InventoryService.time(selection.created_at),expiresAt:InventoryService.time(selection.expires_at),expired:selection.expired,appliedAt:InventoryService.time(selection.applied_at),query:store.parse(selection.query_snapshot.toString())]
    }
    List<Map<String,Object>> members(UUID id) {
        store.jdbc.queryForList('''SELECT e.*,sc.match_status AS signature_status,sc.check_status AS signature_check_status,sc.catalog_revision AS signature_catalog_revision,m.ordinal,m.annotation_version AS captured_version,m.annotation_snapshot,m.accepted_attempt_id AS captured_attempt,m.was_stale
            FROM selection_member m JOIN observation_search e ON e.id=m.entry_id JOIN signature_coverage sc ON sc.entry_id=e.id WHERE m.selection_id=? ORDER BY m.ordinal LIMIT 500''',id)
    }
    boolean changed(Map row) {
        Map snapshot=store.parse(row.annotation_snapshot.toString())
        row.annotation_version!=row.captured_version || row.accepted_attempt_id!=row.captured_attempt || row.stale!=row.was_stale || row.annotations_need_review!=snapshot.needsReview ||
            store.json(annotations.summary(row,true).tags)!=store.json(snapshot.tags)
    }
    boolean analysisChanged(Map selection) {
        Map current=store.one('SELECT current_analysis_id,current_signature_run_id FROM scan WHERE id=?',selection.scan_id)
        current.current_analysis_id!=selection.analysis_id || current.current_signature_run_id!=selection.signature_run_id ||
            store.one('SELECT revision FROM signature_clock WHERE id=1').revision!=selection.signature_catalog_revision
    }
    Map get(UUID id,String actor,String cursor,int limit) {
        store.tx.execute { status ->
            store.one('SELECT id FROM annotation_clock WHERE id=1 FOR SHARE')
            Map selection=owned(id,actor)
            store.one('SELECT id FROM scan WHERE id=? FOR SHARE',selection.scan_id)
            String scope='selection:'+id
            Map page=annotations.inventory.page(scope,cursor,limit,'SELECT max(ordinal) FROM selection_member WHERE selection_id=?',id)
            List<Map<String,Object>> rows=members(id)
            boolean needsReview=analysisChanged(selection) || rows.any { Map row -> changed(row) }
            List<Map<String,Object>> visible=rows.findAll { Map row -> ((Number)row.ordinal).longValue()>((Number)page.after).longValue() }
            boolean more=visible.size()>limit
            if (more) visible=new ArrayList<Map<String,Object>>(visible.subList(0,limit))
            describe(selection)+[needsReview:needsReview,items:visible.collect { Map row -> search.result(row)+[capturedAnnotation:store.parse(row.annotation_snapshot.toString())] },
                nextCursor:more ? annotations.inventory.cursorFor(scope,page.cutoff,visible.getLast().ordinal) : null]
        }
    }
    // Caller holds annotation-clock share lock followed by the scan write lock.
    List<UUID> hashIds(UUID id,String actor,UUID scanId) {
        Map selection=owned(id,actor)
        if (selection.scan_id!=scanId) throw new JobProblem(422,'SELECTION_MISMATCH','The selection belongs to a different scan.')
        if (selection.expired==Boolean.TRUE) throw new JobProblem(409,'SELECTION_EXPIRED','Freeze a new selection before requesting checksums.')
        List<Map<String,Object>> rows=members(id)
        if (analysisChanged(selection) || rows.any { Map row -> changed(row) }) throw new JobProblem(409,'SELECTION_STALE','Selected annotations or evidence changed. Freeze and review a new selection.')
        rows.collect { Map row -> (UUID)row.id }
    }
    Map apply(Map body,String key,String actor,String correlation) {
        Values.fields(body,['selectionId','addTagIds','removeTagIds','reviewState'])
        UUID id=Values.id(body.selectionId)
        List<UUID> add=Values.ids(body.getOrDefault('addTagIds',[])), remove=Values.ids(body.getOrDefault('removeTagIds',[]))
        String state=body.containsKey('reviewState') ? Values.choice(body.reviewState,AnnotationService.STATES) : null
        if (add.isEmpty() && remove.isEmpty() && state==null) Values.invalid('Choose tags or a review-state update.')
        if (!Collections.disjoint(add,remove)) Values.invalid('A tag cannot be added and removed in the same batch.')
        String requestKey=key ?: UUID.randomUUID().toString()
        if (!(requestKey ==~ '[!-~]{1,128}')) Values.invalid('Use a printable ASCII Idempotency-Key of 1 to 128 characters.')
        String payload=Values.hash(store.json([selectionId:id,add:add,remove:remove,reviewState:state]))
        store.tx.execute { status ->
            annotations.clock()
            Map previous=store.one("SELECT payload_hash,response::text AS response FROM idempotency_record WHERE actor=? AND endpoint='/annotation-batches' AND request_key=?",actor,requestKey)
            if (previous!=null) {
                if (previous.payload_hash!=payload) throw new JobProblem(409,'IDEMPOTENCY_CONFLICT','This key was used for a different batch.')
                return store.parse(previous.response.toString())
            }
            Map selection=owned(id,actor)
            store.one('SELECT id FROM scan WHERE id=? FOR SHARE',selection.scan_id)
            if (selection.expired==Boolean.TRUE) throw new JobProblem(409,'SELECTION_EXPIRED','Freeze and review a new selection; this one expired.')
            if (selection.applied_at!=null) throw new JobProblem(409,'SELECTION_APPLIED','This selection was already applied. Freeze a new selection for another edit.')
            List<Map<String,Object>> rows=members(id)
            if (analysisChanged(selection) || rows.any { Map row -> changed(row) }) throw new JobProblem(409,'SELECTION_STALE','Selected annotations or evidence changed. No edits were applied. Freeze and review a new selection.')
            annotations.checkTags(add); annotations.checkTags(remove)
            for (Map row : rows) {
                Map before=annotations.summary(row,true)
                List<UUID> tags=new ArrayList<>()
                for (Object item : (List)before.tags) tags.add(Values.id(((Map)item).id.toString()))
                tags.removeAll(remove)
                for (UUID tag : add) if (!tags.contains(tag)) tags.add(tag)
                if (tags.size()>100) Values.invalid('An observation may have at most 100 manual tags.')
                annotations.write(row,row.memo.toString(),state ?: row.review_state.toString(),tags,actor)
                // Bulk edits do not affirm that an older memo applies to replacement content.
                if (((Number)row.annotation_version).longValue()>0L) store.jdbc.update('UPDATE file_annotation SET baseline_entry_id=?,baseline_attempt_id=? WHERE location_id=?',row.baseline_entry_id,row.baseline_attempt_id,row.location_id)
                annotations.audit(actor,'ANNOTATION_BATCH_ITEM',correlation,[selectionId:id,locationId:row.location_id,beforeVersion:before.version,beforeTags:before.tags,beforeReviewState:before.reviewState,addTagIds:add,removeTagIds:remove,reviewState:state])
            }
            annotations.tick()
            store.jdbc.update('UPDATE selection SET applied_at=clock_timestamp() WHERE id=?',id)
            Map response=[selectionId:id.toString(),updatedCount:rows.size().toString()]
            annotations.audit(actor,'ANNOTATION_BATCH_APPLIED',correlation,response)
            store.jdbc.update("INSERT INTO idempotency_record(actor,endpoint,request_key,payload_hash,response) VALUES (?,'/annotation-batches',?,?,?::jsonb)",actor,requestKey,payload,store.json(response))
            response
        }
    }
}
