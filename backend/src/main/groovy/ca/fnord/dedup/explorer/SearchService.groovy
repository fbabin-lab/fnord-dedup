package ca.fnord.dedup.explorer

import ca.fnord.dedup.inventory.*
import groovy.transform.CompileStatic
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.time.Instant

@CompileStatic
@Service
class SearchService {
    static final Map<String,String> SORTS=[name:'e.name_bytes',path:'e.relative_path_bytes',size:'e.size_bytes',mtime:'(e.mtime_seconds::numeric*1000000000+e.mtime_nanos)',checksumTime:'e.hash_completed_at'].asImmutable()
    static final List<String> FILTERS=['nameContains','pathContains','nameExact','pathExact','nameBytesBase64','pathBytesBase64','extension','sourceId','subtreeLocationId','parentLocationId','entryType','minBytes','maxBytes','mtimeFrom','mtimeTo','checksum','checksumPrefix','hashStatus','duplicateState','duplicateGroupId','tagIds','tagMode','memoContains','reviewState','hasError','stale','annotationsNeedReview'].asImmutable()
    final InventoryStore store
    final AnnotationService annotations
    SearchService(InventoryStore store,AnnotationService annotations) { this.store=store; this.annotations=annotations }
    Map normalize(Map body) {
        Values.fields(body,['filters','sort','direction','limit','cursor'])
        if (body.containsKey('filters') && !(body.filters instanceof Map)) Values.invalid('Filters must be an object.')
        Map raw=(Map)(body.filters ?: [:]); Values.fields(raw,FILTERS)
        Map<String,Object> filters=new TreeMap<>()
        for (Object keyObject : raw.keySet()) {
            String key=keyObject.toString(); Object value=raw.get(key)
            if (key in ['sourceId','subtreeLocationId','parentLocationId','duplicateGroupId']) filters.put(key,Values.id(value).toString())
            else if (key in ['minBytes','maxBytes']) filters.put(key,Long.toString(Values.decimal(value)))
            else if (key in ['hasError','stale','annotationsNeedReview']) {
                if (!(value instanceof Boolean)) Values.invalid('Flag filters must be booleans.')
                filters.put(key,value)
            } else if (key=='tagIds') filters.put(key,Values.ids(value).collect { UUID id -> id.toString() })
            else if (key=='tagMode') filters.put(key,Values.choice(value,['ANY','ALL']))
            else if (key=='entryType') filters.put(key,Values.choice(value,['REGULAR','DIRECTORY','SYMLINK','SPECIAL','UNKNOWN']))
            else if (key=='reviewState') filters.put(key,Values.choice(value,AnnotationService.STATES))
            else if (key=='hashStatus') filters.put(key,Values.choice(value,['ACCEPTED','PENDING','FAILED','STALE','INELIGIBLE','NOT_REQUESTED','NOT_REQUESTED_UNIQUE_SIZE']))
            else if (key=='duplicateState') filters.put(key,Values.choice(value,['DUPLICATE','STALE','NOT_GROUPED']))
            else if (key in ['mtimeFrom','mtimeTo']) {
                String text=Values.text(value,40,false)
                try { if (!text.endsWith('Z')) throw new IllegalArgumentException(); filters.put(key,Instant.parse(text).toString()) }
                catch (Exception ignored) { Values.invalid('Date bounds must be UTC ISO instants ending in Z.') }
            } else if (key in ['checksum','checksumPrefix']) {
                String hex=Values.text(value,64,false).toLowerCase(Locale.ROOT)
                if (!(hex ==~ '[0-9a-f]{1,64}') || (key=='checksum' && hex.length()!=64)) Values.invalid('Use a hexadecimal SHA-256 digest or an explicit prefix.')
                filters.put(key,hex)
            } else if (key in ['nameBytesBase64','pathBytesBase64']) {
                String encoded=Values.text(value,44000)
                try {
                    byte[] bytes=Base64.decoder.decode(encoded)
                    if (bytes.length>(key=='nameBytesBase64' ? 255 : 32768)) throw new IllegalArgumentException()
                    filters.put(key,Base64.encoder.encodeToString(bytes))
                } catch (Exception ignored) { Values.invalid('The exact raw path/name must be bounded base64.') }
            } else filters.put(key,Values.text(value,4096))
        }
        if (filters.minBytes!=null && filters.maxBytes!=null && Long.parseLong(filters.minBytes.toString())>Long.parseLong(filters.maxBytes.toString())) Values.invalid('Minimum bytes must not exceed maximum bytes.')
        if (filters.mtimeFrom!=null && filters.mtimeTo!=null && Instant.parse(filters.mtimeFrom.toString()).compareTo(Instant.parse(filters.mtimeTo.toString()))>=0) Values.invalid('The UTC date interval must have a positive length.')
        if (filters.containsKey('tagIds')) filters.putIfAbsent('tagMode','ANY')
        [filters:filters,sort:Values.choice(body.getOrDefault('sort','path'),SORTS.keySet()),direction:Values.choice(body.getOrDefault('direction','ASC'),['ASC','DESC'])]
    }
    // Every token binds a compact immutable cutoff to its query and mutable input revisions.
    Map capture(UUID scanId,Map query,String token=null) {
        Map clock=store.one('SELECT revision FROM annotation_clock WHERE id=1 FOR SHARE')
        Map scan=store.one('SELECT * FROM scan WHERE id=? FOR SHARE',scanId)
        if (scan==null) throw new JobProblem(404,'SCAN_NOT_FOUND','The scan does not exist.')
        Map current=[queries:store.one('SELECT revision FROM search_clock WHERE id=1').revision.toString(),scan:scanId.toString(),query:Values.hash(store.json(query)),evidence:scan.evidence_revision.toString(),revision:scan.query_revision.toString(),annotations:clock.revision.toString(),analysis:scan.current_analysis_id?.toString()]
        if (token!=null) {
            Map previous=decode(token)
            for (Object key : current.keySet()) if (previous.get(key)!=current.get(key)) throw new JobProblem(409,'CURSOR_STALE','The query or its evidence/annotations changed. Refresh the result list.')
            Values.decimal(previous.cutoff)
            if (previous.after!=null) Values.id(previous.after)
            return previous
        }
        current+[cutoff:store.jdbc.queryForObject('SELECT coalesce(max(sequence),0) FROM scan_entry WHERE scan_id=?',Long,scanId).toString()]
    }
    void unchanged(Map context) {
        if (store.one('SELECT revision FROM search_clock WHERE id=1').revision.toString()!=context.queries)
            throw new JobProblem(409,'CURSOR_STALE','Evidence changed while reading this view. Refresh the result list.')
    }
    String token(Map context) { Base64.urlEncoder.withoutPadding().encodeToString(store.json(context).getBytes(StandardCharsets.UTF_8)) }
    Map decode(String token) {
        try {
            if (token.length()>2048) throw new IllegalArgumentException()
            return store.parse(new String(Base64.urlDecoder.decode(token),StandardCharsets.UTF_8))
        } catch (Exception ignored) { throw new JobProblem(422,'INVALID_CURSOR','The view token is invalid.') }
    }
    Map search(UUID scanId,Map body) {
        Map query=normalize(body)
        int limit=100
        if (body.containsKey('limit')) {
            if (!(body.limit instanceof Integer)) Values.invalid('Page size must be an integer.')
            limit=((Number)body.limit).intValue()
        }
        if (limit<1 || limit>500) Values.invalid('Page size must be between 1 and 500.')
        String cursor=body.cursor==null ? null : Values.text(body.cursor,2048,false)
        int pageLimit=limit
        store.tx.execute { status ->
            Map context=capture(scanId,query,cursor)
            List<Map<String,Object>> rows=rows(scanId,query,context,pageLimit+1)
            unchanged(context)
            boolean more=rows.size()>pageLimit; if (more) rows.removeLast()
            Map view=new LinkedHashMap(context); view.remove('after')
            [items:rows.collect { Map row -> result(row) },nextCursor:more ? token(view+([after:rows.getLast().id.toString()] as Map)) : null,viewToken:token(view),
             evidenceRevision:context.evidence,annotationRevision:context.annotations,analysisId:context.analysis]
        }
    }
    Map result(Map row) {
        InventoryService.observationRow(row)+[hashStatus:row.hash_status,checksum:row.hash_status=='ACCEPTED' ? HexFormat.of().formatHex((byte[])row.digest) : null,
            checksumCompletedAt:InventoryService.time(row.hash_completed_at),duplicateState:row.duplicate_state,duplicateGroupId:row.duplicate_group_id,
            stale:row.stale,hasError:row.has_error,annotation:annotations.summary(row)]
    }
    List<Map<String,Object>> rows(UUID scanId,Map query,Map context,int limit,List<UUID> ids=null) {
        Map f=(Map)query.filters
        String cte=''
        List<Object> args=new ArrayList<>()
        if (f.subtreeLocationId!=null) {
            cte='WITH RECURSIVE subtree(id) AS (SELECT id FROM file_location WHERE id=? UNION SELECT l.id FROM file_location l JOIN subtree p ON l.parent_id=p.id) '
            args.add(Values.id(f.subtreeLocationId))
        }
        List<String> conditions=['e.scan_id=?','e.sequence<=?']
        args.add(scanId); args.add(Long.parseLong(context.cutoff.toString()))
        if (f.subtreeLocationId!=null) conditions.add('e.location_id IN (SELECT id FROM subtree)')
        Map<String,String> equals=[sourceId:'source_id',parentLocationId:'parent_id',entryType:'entry_type',extension:'extension',hashStatus:'hash_status',duplicateState:'duplicate_state',duplicateGroupId:'duplicate_group_id',reviewState:'review_state',hasError:'has_error',stale:'stale',annotationsNeedReview:'annotations_need_review']
        for (Map.Entry<String,String> item : equals.entrySet()) if (f.containsKey(item.key)) {
            conditions.add('e.'+item.value+'=?')
            args.add(item.key in ['sourceId','parentLocationId','duplicateGroupId'] ? Values.id(f.get(item.key)) : f.get(item.key))
        }
        for (String key : ['nameContains','pathContains','nameExact','pathExact','nameBytesBase64','pathBytesBase64']) if (f.containsKey(key)) {
            String column=key.startsWith('name') ? 'e.name_bytes' : 'e.relative_path_bytes'
            conditions.add(key.endsWith('Contains') ? 'position(?::bytea in '+column+')>0' : column+'=?::bytea')
            args.add(key.endsWith('Base64') ? Base64.decoder.decode(f.get(key).toString()) : f.get(key).toString().getBytes(StandardCharsets.UTF_8))
        }
        if (f.memoContains!=null) { conditions.add('position(? in e.memo)>0'); args.add(f.memoContains) }
        for (String key : ['minBytes','maxBytes']) if (f.containsKey(key)) { conditions.add('e.size_bytes'+(key=='minBytes' ? '>=' : '<=')+'?'); args.add(Long.parseLong(f.get(key).toString())) }
        for (String key : ['mtimeFrom','mtimeTo']) if (f.containsKey(key)) {
            Instant instant=Instant.parse(f.get(key).toString())
            conditions.add(SORTS.mtime+(key=='mtimeFrom' ? '>=' : '<')+'?')
            args.add(BigDecimal.valueOf(instant.epochSecond).multiply(BigDecimal.valueOf(1000000000L)).add(BigDecimal.valueOf(instant.nano)))
        }
        for (String key : ['checksum','checksumPrefix']) if (f.containsKey(key)) {
            conditions.add("e.hash_status='ACCEPTED' AND encode(e.digest,'hex')"+(key=='checksum' ? '=?' : ' LIKE ?'))
            args.add(f.get(key).toString()+(key=='checksum' ? '' : '%'))
        }
        List tagIds=(List)(f.tagIds ?: [])
        if (!tagIds.isEmpty()) {
            String placeholders=Collections.nCopies(tagIds.size(),'?').join(',')
            conditions.add('(SELECT count(*) FROM file_annotation_tag nt WHERE nt.location_id=e.location_id AND nt.tag_id IN ('+placeholders+'))'+(f.tagMode=='ALL' ? '='+tagIds.size() : '>0'))
            for (Object id : tagIds) args.add(Values.id(id))
        }
        if (ids!=null) {
            conditions.add('e.id IN ('+Collections.nCopies(ids.size(),'?').join(',')+')')
            args.addAll(ids)
        }
        String sort=SORTS.get(query.sort.toString()), direction=query.direction.toString()
        if (context.after!=null) {
            Map anchor=store.one('SELECT '+sort+' AS value FROM observation_search e WHERE e.id=? AND e.scan_id=? AND e.sequence<=?',Values.id(context.after),scanId,Long.parseLong(context.cutoff.toString()))
            if (anchor==null) throw new JobProblem(409,'CURSOR_STALE','The cursor anchor is no longer available.')
            String cmp=direction=='ASC' ? '>' : '<'
            if (anchor.value==null) { conditions.add('('+sort+' IS NULL AND e.id'+cmp+'?)'); args.add(Values.id(context.after)) }
            else {
                conditions.add('('+sort+cmp+'? OR ('+sort+'=? AND e.id'+cmp+'?) OR '+sort+' IS NULL)')
                args.add(anchor.value); args.add(anchor.value); args.add(Values.id(context.after))
            }
        }
        args.add(limit)
        store.jdbc.queryForList(cte+'SELECT e.* FROM observation_search e WHERE '+conditions.join(' AND ')+' ORDER BY '+sort+' '+direction+' NULLS LAST,e.id '+direction+' LIMIT ?',args.toArray())
    }
    Map directory(UUID scan,UUID location) {
        Map parent=store.one("SELECT l.id,l.display_path,e.directory_coverage FROM file_location l JOIN scan_entry e ON e.location_id=l.id WHERE e.scan_id=? AND l.id=? AND e.entry_type='DIRECTORY'",scan,location)
        if (parent==null) throw new JobProblem(404,'DIRECTORY_NOT_OBSERVED','Select a directory observed in this scan.')
        List<Map<String,Object>> ancestors=store.jdbc.queryForList('''WITH RECURSIVE ancestors AS (
            SELECT id,parent_id,display_name,display_path,0 AS depth FROM file_location WHERE id=?
            UNION ALL SELECT l.id,l.parent_id,l.display_name,l.display_path,p.depth+1 FROM file_location l JOIN ancestors p ON l.id=p.parent_id WHERE p.depth<256)
            SELECT * FROM ancestors ORDER BY depth DESC''',location)
        if (ancestors.size()>256) throw new JobProblem(422,'DIRECTORY_DEPTH_LIMIT','This path exceeds the breadcrumb depth limit of 256. Search by path instead.')
        [locationId:location,path:parent.display_path,coverage:parent.directory_coverage,breadcrumbs:ancestors.collect { Map row -> [locationId:row.id,name:row.display_name,path:row.display_path] }]
    }
}
