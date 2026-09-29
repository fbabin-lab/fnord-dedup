package ca.fnord.dedup.signatures

import ca.fnord.dedup.explorer.*
import ca.fnord.dedup.inventory.*
import groovy.transform.CompileStatic
import org.springframework.stereotype.Service
import org.apache.commons.csv.*
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

@CompileStatic
@Service
class SignatureExchange {
    static final List<String> HEADERS=['schemaVersion','id','revision','name','memo','tagsJson','sizeBytes','algorithm','checksum','filename','filenameBytesBase64','filenameMatchMode','enabled'].asImmutable()
    static final JsonMapper PARSER=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
    final InventoryStore store
    final SignatureCatalog catalog
    final AnnotationService annotations
    final InventoryService inventory
    final SignatureLimits limits
    SignatureExchange(InventoryStore store,SignatureCatalog catalog,AnnotationService annotations,InventoryService inventory,SignatureLimits limits) { this.store=store; this.catalog=catalog; this.annotations=annotations; this.inventory=inventory; this.limits=limits }
    byte[] bounded(InputStream stream) {
        byte[] bytes=stream.readNBytes(limits.importBytes+1)
        if (bytes.length>limits.importBytes) throw new JobProblem(413,'IMPORT_TOO_LARGE','The catalog upload exceeds the configured byte limit.')
        bytes
    }
    Map stage(byte[] bytes,String format,String policy,String actor,String correlation) {
        Values.choice(format,['JSON','CSV']); Values.choice(policy,['REJECT_EXISTING_ID','UPDATE_BY_ID'])
        if (bytes.length>limits.importBytes) throw new JobProblem(413,'IMPORT_TOO_LARGE','The catalog upload exceeds the configured byte limit.')
        String text
        try { text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (Exception ignored) { Values.invalid('Catalog uploads must be valid UTF-8.'); return [:] }
        List<Map> rows=new ArrayList<>()
        try {
            if (format=='JSON') {
                Object value=PARSER.readValue(text,Object)
                if (!(value instanceof Map)) Values.invalid('Use a JSON catalog envelope.')
                Map envelope=(Map)value; Values.fields(envelope,['schemaVersion','exportedAt','signatures'])
                if (envelope.schemaVersion!=1 || !(envelope.signatures instanceof List)) Values.invalid('Use schemaVersion 1 with a signatures array.')
                if (envelope.exportedAt!=null) {
                    String time=Values.text(envelope.exportedAt,40,false)
                    if (!time.endsWith('Z')) Values.invalid('exportedAt must be a UTC timestamp.')
                    Instant.parse(time)
                }
                for (Object raw : (List)envelope.signatures) {
                    if (rows.size()>=limits.importRows) throw new JobProblem(413,'IMPORT_TOO_MANY_ROWS','The catalog exceeds the configured row limit.')
                    rows.add(raw instanceof Map ? [raw:raw] : [error:'A signature record must be an object.'])
                }
            } else {
                try (CSVParser parser=CSVFormat.RFC4180.parse(new StringReader(text))) {
                    Iterator<CSVRecord> iterator=parser.iterator()
                    if (!iterator.hasNext() || iterator.next().toList()!=HEADERS) Values.invalid('CSV must have the exact documented header, once, without duplicate or ambiguous names.')
                    while (iterator.hasNext()) {
                        if (rows.size()>=limits.importRows) throw new JobProblem(413,'IMPORT_TOO_MANY_ROWS','The catalog exceeds the configured row limit.')
                        CSVRecord record=iterator.next()
                        if (record.size()!=HEADERS.size()) { rows.add([error:'CSV row has an incorrect number of cells.']); continue }
                        Map raw=new LinkedHashMap()
                        for (int i=0;i<HEADERS.size();i++) raw.put(HEADERS.get(i),record.get(i))
                        try {
                            if (raw.schemaVersion!='1') Values.invalid('Only schemaVersion 1 is supported.')
                            raw.put('schemaVersion',1)
                            if (raw.filenameBytesBase64.toString().startsWith("'+")) raw.put('filenameBytesBase64',raw.filenameBytesBase64.toString().substring(1))
                            raw.put('tags',raw.tagsJson=='' ? [] : PARSER.readValue(raw.tagsJson.toString(),List)); raw.remove('tagsJson')
                            if (!(raw.enabled in ['','true','false'])) Values.invalid('Enabled must be true or false.')
                            raw.put('enabled',raw.enabled!='false')
                            if (raw.filenameMatchMode=='') raw.remove('filenameMatchMode')
                            rows.add([raw:raw])
                        } catch (Exception ignored) { rows.add([error:'Invalid schemaVersion, tagsJson, or enabled cell.']) }
                    }
                }
            }
        } catch (JobProblem problem) {
            if (problem.status==413) throw problem
            rows.clear(); rows.add([error:problem.message])
        } catch (Exception ignored) { rows.clear(); rows.add([error:'Malformed catalog. Use the documented JSON envelope or RFC 4180 CSV.']) }
        if (rows.isEmpty()) rows.add([error:'The catalog must contain at least one record.'])
        store.tx.execute { tx ->
            store.one('SELECT id FROM job_admission WHERE id=1 FOR UPDATE')
            store.one('SELECT revision FROM signature_clock WHERE id=1 FOR SHARE')
            if (store.jdbc.queryForObject("SELECT count(*) FROM signature_import WHERE actor=? AND state IN ('VALID','INVALID') AND expires_at>clock_timestamp()",Long,actor)>=20L)
                throw new JobProblem(429,'IMPORT_CAPACITY','At most 20 unapplied imports per actor may be staged within 24 hours.')
            Set<UUID> ids=new HashSet<>()
            int errors=0
            for (Map row : rows) {
                if (row.error==null) {
                    try {
                        Map raw=(Map)row.raw
                        UUID id=raw.id==null || raw.id=='' ? UUID.randomUUID() : Values.id(raw.id)
                        if (!ids.add(id)) Values.invalid('Duplicate record ID within this upload.')
                        Map existing=store.one('SELECT revision FROM signature WHERE id=?',id)
                        long expected=0L
                        if (policy=='UPDATE_BY_ID') {
                            if (raw.id==null || raw.id=='' || existing==null || raw.revision==null || raw.revision=='') Values.invalid('UPDATE_BY_ID requires an existing ID and expected revision on every row.')
                            expected=recordRevision(raw.revision)
                            if (((Number)existing.revision).longValue()!=expected) Values.invalid('Expected signature revision is stale.')
                        } else {
                            if (existing!=null) Values.invalid('This ID already exists; choose UPDATE_BY_ID explicitly to update it.')
                            if (raw.revision!=null && raw.revision!='' && recordRevision(raw.revision)<1L) Values.invalid('Record revision must be positive.')
                        }
                        Map normalized=SignatureCatalog.normalize(raw)
                        normalized.put('id',id.toString()); normalized.put('expectedRevision',Long.toString(expected))
                        row.put('proposed',normalized)
                    } catch (JobProblem problem) { row.put('error',problem.message) }
                }
                if (row.error!=null) errors++
            }
            UUID id=UUID.randomUUID()
            store.jdbc.update('''INSERT INTO signature_import(id,actor,format,policy,catalog_revision,row_count,error_count,state)
                VALUES (?,?,?,?,?,?,?,?)''',id,actor,format,policy,catalog.revision(),rows.size(),errors,errors==0 ? 'VALID' : 'INVALID')
            int ordinal=0
            for (Map row : rows) store.jdbc.update('INSERT INTO signature_import_row(import_id,ordinal,proposed,error) VALUES (?,?,?::jsonb,?)',id,++ordinal,row.proposed==null ? null : store.json(row.proposed),row.error)
            annotations.audit(actor,'SIGNATURE_IMPORT_STAGED',correlation,[importId:id,format:format,policy:policy,rowCount:rows.size(),errorCount:errors])
            get(id,actor,null,100)
        }
    }
    private static long recordRevision(Object value) {
        if (!(value instanceof String || value instanceof Integer || value instanceof Long || value instanceof BigInteger)) Values.invalid('Revision must be an exact positive integer.')
        long revision=Values.decimal(value.toString())
        if (revision<1L) Values.invalid('Revision must be positive.')
        revision
    }
    Map owned(UUID id,String actor) {
        Map row=store.one('SELECT *,expires_at>clock_timestamp() AS valid FROM signature_import WHERE id=? AND actor=?',id,actor)
        if (row==null) throw new JobProblem(404,'IMPORT_NOT_FOUND','This staged import does not exist.')
        row
    }
    Map get(UUID id,String actor,String cursor,int limit) {
        Map row=owned(id,actor)
        String scope='signature-import:'+id
        Map page=inventory.page(scope,cursor,limit,'SELECT coalesce(max(ordinal),0) FROM signature_import_row WHERE import_id=?',id)
        List<Map<String,Object>> rows=store.jdbc.queryForList('SELECT * FROM signature_import_row WHERE import_id=? AND ordinal>? AND ordinal<=? ORDER BY ordinal LIMIT ?',id,page.after,page.cutoff,limit+1)
        boolean more=rows.size()>limit; if (more) rows.removeLast()
        [id:id,state:row.state,format:row.format,policy:row.policy,catalogRevision:row.catalog_revision.toString(),rowCount:row.row_count,errorCount:row.error_count,
         expiresAt:InventoryService.time(row.expires_at),appliedCatalogRevision:row.applied_catalog_revision?.toString(),
         items:rows.collect { Map r -> [row:r.ordinal,error:r.error,proposed:r.proposed==null ? null : store.parse(r.proposed.toString())] },
         nextCursor:more ? inventory.cursorFor(scope,page.cutoff,rows.getLast().ordinal) : null]
    }
    Map apply(UUID id,Map body,String key,String actor,String correlation) {
        Values.fields(body,['expectedCatalogRevision'])
        long expected=Values.decimal(body.expectedCatalogRevision)
        String requestKey=key ?: UUID.randomUUID().toString()
        if (!(requestKey ==~ '[!-~]{1,128}')) Values.invalid('Invalid Idempotency-Key.')
        String endpoint='/signature-imports/'+id+'/apply', hash=Values.hash(store.json([expectedCatalogRevision:Long.toString(expected)]))
        store.tx.execute { tx ->
            annotations.clock(); catalog.revision(true)
            Map previous=store.one('SELECT * FROM idempotency_record WHERE actor=? AND endpoint=? AND request_key=?',actor,endpoint,requestKey)
            if (previous!=null) {
                if (previous.payload_hash!=hash) throw new JobProblem(409,'IDEMPOTENCY_CONFLICT','This key was used for a different apply request.')
                return store.parse(previous.response.toString())
            }
            Map row=owned(id,actor)
            Map result
            if (row.state=='APPLIED') {
                if (expected!=((Number)row.catalog_revision).longValue()) throw new JobProblem(409,'CATALOG_CONFLICT','Use the original staged catalog revision when retrying an applied import.')
                result=[id:id.toString(),state:'APPLIED',catalogRevision:row.applied_catalog_revision.toString(),rowCount:row.row_count]
            } else {
                if (row.valid!=Boolean.TRUE || row.state!='VALID') throw new JobProblem(422,'IMPORT_NOT_APPLICABLE','Only a valid, unexpired dry run can be applied. Correct all rows and stage again.')
                if (expected!=((Number)row.catalog_revision).longValue()) throw new JobProblem(409,'CATALOG_CONFLICT','Use the staged base catalog revision.')
                catalog.requireRevision(expected)
                long next=catalog.advance()
                int after=0
                while (true) {
                    List<Map<String,Object>> rows=store.jdbc.queryForList('SELECT * FROM signature_import_row WHERE import_id=? AND ordinal>? ORDER BY ordinal LIMIT 100',id,after)
                    if (rows.isEmpty()) break
                    for (Map r : rows) {
                        Map proposed=store.parse(r.proposed.toString())
                        catalog.write(Values.id(proposed.id),proposed,Values.decimal(proposed.expectedRevision),next,'IMPORT')
                    }
                    after=((Number)rows.getLast().ordinal).intValue()
                }
                store.jdbc.update("UPDATE signature_import SET state='APPLIED',applied_at=clock_timestamp(),applied_catalog_revision=? WHERE id=?",next,id)
                result=[id:id.toString(),state:'APPLIED',catalogRevision:Long.toString(next),rowCount:row.row_count]
                annotations.audit(actor,'SIGNATURE_IMPORT_APPLIED',correlation,result)
            }
            store.jdbc.update('INSERT INTO idempotency_record(actor,endpoint,request_key,payload_hash,response) VALUES (?,?,?,?,?::jsonb)',actor,endpoint,requestKey,hash,store.json(result))
            result
        }
    }
    static Map exportRecord(InventoryStore store,Map row) {
        List<Map> tags=(List<Map>)store.mapper.readValue(row.tags.toString(),List)
        [schemaVersion:1,id:row.signature_id.toString(),revision:row.revision.toString(),name:row.name,memo:row.memo,tags:tags.collect { Map t -> t.label },
         sizeBytes:row.size_bytes.toString(),algorithm:row.algorithm,checksum:HexFormat.of().formatHex((byte[])row.digest),filename:row.filename,
         filenameBytesBase64:row.filename_bytes==null ? null : Base64.encoder.encodeToString((byte[])row.filename_bytes),filenameMatchMode:row.filename_match_mode,enabled:row.enabled,sourceNote:row.source_note]
    }
    /** CSV is spreadsheet-safe display data. JSON preserves exact metadata; basename base64 remains exact in both. */
    static String safeCell(Object value) {
        String text=value==null ? '' : value.toString()
        int first=0
        while (first<text.length() && (Character.isWhitespace(text.charAt(first)) || Character.isSpaceChar(text.charAt(first)) || Character.getType(text.charAt(first))==Character.FORMAT || Character.isISOControl(text.charAt(first)) || text.charAt(first)==(char)0xfeff)) first++
        boolean control=false
        for (int i=0;i<first;i++) if (Character.isISOControl(text.charAt(i))) control=true
        control || (first<text.length() && '=+-@'.indexOf((int)text.charAt(first))>=0) ? "'"+text : text
    }
    static String csv(InventoryStore store,List<Map> records,boolean header=false) {
        StringWriter writer=new StringWriter()
        try (CSVPrinter printer=new CSVPrinter(writer,CSVFormat.RFC4180)) {
            if (header) printer.printRecord(HEADERS)
            for (Map record : records) {
                Map row=new LinkedHashMap(record); row.put('tagsJson',store.json(record.tags)); row.remove('tags')
                printer.printRecord(HEADERS.collect { String h -> safeCell(row.get(h)) })
            }
        }
        writer.toString()
    }
}
