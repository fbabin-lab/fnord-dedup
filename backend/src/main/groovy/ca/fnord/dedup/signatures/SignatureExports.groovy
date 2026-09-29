package ca.fnord.dedup.signatures

import ca.fnord.dedup.explorer.*
import ca.fnord.dedup.inventory.*
import groovy.transform.CompileStatic
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@CompileStatic
@Service
class SignatureExports {
    final InventoryStore store
    final SignatureCatalog catalog
    final AnnotationService annotations
    final SignatureLimits limits
    SignatureExports(InventoryStore store,SignatureCatalog catalog,AnnotationService annotations,SignatureLimits limits) { this.store=store; this.catalog=catalog; this.annotations=annotations; this.limits=limits }
    Map create(Map body,String key,String actor,String correlation) {
        Values.fields(body,['format','catalogRevision'])
        String format=Values.choice(body.format,['JSON','CSV'])
        long revision=Values.decimal(body.catalogRevision)
        String requestKey=key ?: UUID.randomUUID().toString(), hash=Values.hash(store.json([format:format,catalogRevision:Long.toString(revision)]))
        if (!(requestKey ==~ '[!-~]{1,128}')) Values.invalid('Invalid Idempotency-Key.')
        store.tx.execute { tx ->
            store.one('SELECT id FROM job_admission WHERE id=1 FOR UPDATE')
            Map previous=store.one("SELECT * FROM idempotency_record WHERE actor=? AND endpoint='/signature-exports' AND request_key=?",actor,requestKey)
            if (previous!=null) {
                if (previous.payload_hash!=hash) throw new JobProblem(409,'IDEMPOTENCY_CONFLICT','This key was used for a different export.')
                return store.parse(previous.response.toString())
            }
            if (revision>catalog.revision()) Values.invalid('This catalog revision does not exist.')
            BigDecimal reserved=store.jdbc.queryForObject("SELECT coalesce(sum(max_bytes),0) FROM signature_export WHERE state NOT IN ('FAILED','CANCELLED')",BigDecimal)
            if (reserved.add(BigDecimal.valueOf(limits.exportBytes))>BigDecimal.valueOf(limits.exportQuotaBytes)) throw new JobProblem(429,'EXPORT_QUOTA','The reserved catalog artifact quota is exhausted.')
            UUID id=UUID.randomUUID()
            store.jdbc.update("INSERT INTO signature_export(id,actor,catalog_revision,format,state,max_bytes) VALUES (?,?,?,?,'QUEUED',?)",id,actor,revision,format,limits.exportBytes)
            Map response=get(id,actor)
            store.jdbc.update("INSERT INTO idempotency_record(actor,endpoint,request_key,payload_hash,response) VALUES (?,'/signature-exports',?,?,?::jsonb)",actor,requestKey,hash,store.json(response))
            annotations.audit(actor,'SIGNATURE_EXPORT_QUEUED',correlation,[exportId:id,catalogRevision:Long.toString(revision),format:format])
            response
        }
    }
    Map owned(UUID id,String actor) {
        Map row=store.one('SELECT * FROM signature_export WHERE id=? AND actor=?',id,actor)
        if (row==null) throw new JobProblem(404,'EXPORT_NOT_FOUND','This catalog export does not exist.')
        row
    }
    Map get(UUID id,String actor) {
        Map row=owned(id,actor)
        [id:id,state:row.state,format:row.format,catalogRevision:row.catalog_revision.toString(),rowCount:row.row_count.toString(),byteCount:row.byte_count.toString(),
         sha256:row.digest==null ? null : HexFormat.of().formatHex((byte[])row.digest),errorCode:row.error_code,createdAt:InventoryService.time(row.created_at),completedAt:InventoryService.time(row.completed_at)]
    }
    Map control(UUID id,String action,String actor,String correlation) {
        store.tx.execute { tx ->
            owned(id,actor)
            Map row=store.one('SELECT * FROM signature_export WHERE id=? FOR UPDATE',id)
            String state=Values.choice(action,['pause','resume','cancel'])
            if (row.state in ['READY','CANCELLED','FAILED']) throw new JobProblem(409,'EXPORT_TERMINAL','This export is already terminal.')
            state=state=='cancel' ? 'CANCELLED' : state=='pause' ? 'PAUSED' : 'BUILDING'
            store.jdbc.update('UPDATE signature_export SET state=? WHERE id=?',state,id)
            if (state=='CANCELLED') store.jdbc.update('DELETE FROM signature_export_chunk WHERE export_id=?',id)
            annotations.audit(actor,'SIGNATURE_EXPORT_'+action.toUpperCase(Locale.ROOT),correlation,[exportId:id])
            get(id,actor)
        }
    }
    void download(UUID id,String actor,OutputStream out) {
        Map row=owned(id,actor)
        if (row.state!='READY') throw new JobProblem(409,'EXPORT_NOT_READY','Only a complete published artifact can be downloaded.')
        int after=0
        while (true) {
            List<Map<String,Object>> chunks=store.jdbc.queryForList('SELECT * FROM signature_export_chunk WHERE export_id=? AND ordinal>? ORDER BY ordinal LIMIT 1',id,after)
            if (chunks.isEmpty()) break
            Map chunk=chunks.getFirst(); out.write((byte[])chunk.content); after=((Number)chunk.ordinal).intValue()
        }
    }
    /** Called inside InventoryStore.claim's scheduler lease transaction, with no native handles. */
    static boolean advance(InventoryStore store) {
        Map export=store.one("SELECT * FROM signature_export WHERE state IN ('QUEUED','BUILDING') ORDER BY sequence LIMIT 1 FOR UPDATE")
        if (export==null) return false
        UUID id=(UUID)export.id
        long after=((Number)export.after_sequence).longValue(), count=((Number)export.row_count).longValue()
        int ordinal=store.jdbc.queryForObject('SELECT coalesce(max(ordinal),0) FROM signature_export_chunk WHERE export_id=?',Integer,id)+1
        String chunk=''
        if (ordinal==1) chunk=export.format=='JSON' ? '{"schemaVersion":1,"exportedAt":'+store.json(InventoryService.time(export.created_at))+',"signatures":[' : SignatureExchange.csv(store,[],true)
        List<Map<String,Object>> rows=store.jdbc.queryForList('''SELECT r.*,s.sequence FROM signatures_at(?) r JOIN signature s ON s.id=r.signature_id
            WHERE s.sequence>? ORDER BY s.sequence LIMIT 100''',export.catalog_revision,after)
        List<Map> records=rows.collect { Map row -> SignatureExchange.exportRecord(store,row) }
        if (export.format=='JSON') {
            for (Map record : records) { if (count++>0L) chunk+=','; chunk+=store.json(record) }
            if (rows.isEmpty()) chunk+=']}'
        } else chunk+=SignatureExchange.csv(store,records)
        byte[] bytes=chunk.getBytes(StandardCharsets.UTF_8)
        if (((Number)export.byte_count).longValue()+bytes.length>((Number)export.max_bytes).longValue()) {
            store.jdbc.update("UPDATE signature_export SET state='FAILED',error_code='EXPORT_TOO_LARGE',completed_at=clock_timestamp() WHERE id=?",id)
            store.jdbc.update('DELETE FROM signature_export_chunk WHERE export_id=?',id)
            return true
        }
        store.jdbc.update('INSERT INTO signature_export_chunk(export_id,ordinal,content) VALUES (?,?,?)',id,ordinal,bytes)
        store.jdbc.update("UPDATE signature_export SET state='BUILDING',after_sequence=?,row_count=row_count+?,byte_count=byte_count+? WHERE id=?",rows.isEmpty() ? after : rows.getLast().sequence,rows.size(),bytes.length,id)
        if (rows.isEmpty()) {
            MessageDigest digest=MessageDigest.getInstance('SHA-256')
            // At most the reserved byte cap is processed; one bounded chunk is in memory at a time.
            for (int n=1;n<=ordinal;n++) digest.update((byte[])store.one('SELECT content FROM signature_export_chunk WHERE export_id=? AND ordinal=?',id,n).content)
            store.jdbc.update("UPDATE signature_export SET state='READY',digest=?,completed_at=clock_timestamp() WHERE id=?",digest.digest(),id)
        }
        true
    }
}
