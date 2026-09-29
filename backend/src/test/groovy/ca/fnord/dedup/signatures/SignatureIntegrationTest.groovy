package ca.fnord.dedup.signatures

import ca.fnord.dedup.inventory.*

import ca.fnord.dedup.explorer.*
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.*
import java.security.MessageDigest
import static org.junit.jupiter.api.Assertions.*

@Tag('postgres')
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignatureIntegrationTest {
    PostgreSQLContainer database
    HikariDataSource pool
    JdbcTemplate jdbc
    InventoryStore store
    InventoryService inventory
    HashService hashes
    AnnotationService annotations
    SearchService search
    SelectionService selections
    InventoryIntegrationTest.FixtureRegistry sources
    SignatureCatalog catalog
    SignatureService signatures
    SignatureExchange exchange
    SignatureExports exports
    SignatureLimits limits=new SignatureLimits()
    InventoryWorker worker
    @TempDir Path fixture
    UUID sourceId
    String correlation=UUID.randomUUID().toString()
    @BeforeAll void database() {
        String url=System.getenv('FNORD_TEST_DB_URL')
        if (!url) {
            database=new PostgreSQLContainer('postgres:18.6-bookworm@sha256:3725f4e2499eef5134592b3b4ab79a543ed7f8e533b05b5b637af926630f6650').withDatabaseName('fnord').withUsername('fnord').withPassword('generated-disposable-test-password')
            database.start(); url=database.jdbcUrl
        }
        def ds=new DriverManagerDataSource(url,System.getenv('FNORD_TEST_DB_USER') ?: 'fnord',System.getenv('FNORD_TEST_DB_PASSWORD') ?: 'generated-disposable-test-password')
        new JdbcTemplate(ds).execute('CREATE SCHEMA IF NOT EXISTS signature_test')
        pool=new HikariDataSource(); pool.jdbcUrl=url; pool.username=ds.username; pool.password=ds.password; pool.maximumPoolSize=2
        pool.connectionInitSql='SET search_path=signature_test'
        def flyway=Flyway.configure().dataSource(pool).schemas('signature_test').defaultSchema('signature_test')
        if (System.getenv('SPRING_FLYWAY_POSTGRESQL_TRANSACTIONAL_LOCK')=='false') flyway.configuration(['flyway.postgresql.transactional.lock':'false'])
        flyway.load().migrate()
        jdbc=new JdbcTemplate(pool)
    }
    @AfterAll void close() { pool?.close(); database?.stop() }
    @BeforeEach void reset() {
        jdbc.execute('TRUNCATE source_configuration,idempotency_record,signature_import,signature_export,signature,tag,audit_event RESTART IDENTITY CASCADE')
        jdbc.execute('UPDATE annotation_clock SET revision=0')
        jdbc.execute('UPDATE signature_clock SET revision=0')
        limits=new SignatureLimits()
        jdbc.execute("UPDATE scheduler_lock SET owner=NULL,token=0,expires_at='-infinity'")
        store=new InventoryStore(jdbc,new TransactionTemplate(new JdbcTransactionManager(pool)),JsonMapper.builder().build())
        sources=new InventoryIntegrationTest.FixtureRegistry(store); sources.allowBodyReads=true; sourceId=sources.add(fixture)
        inventory=new InventoryService(store,sources); hashes=new HashService(store,inventory,new SelectionService(store,new SearchService(store,new AnnotationService(store,inventory)),new AnnotationService(store,inventory))); worker=new InventoryWorker(store,sources)
        annotations=new AnnotationService(store,inventory); search=new SearchService(store,annotations); selections=new SelectionService(store,search,annotations)
        catalog=new SignatureCatalog(store,inventory,annotations); signatures=new SignatureService(store,inventory,catalog,annotations)
        exchange=new SignatureExchange(store,catalog,annotations,inventory,limits); exports=new SignatureExports(store,catalog,annotations,limits)
    }
    Map create(List ids=[sourceId]) { inventory.create([name:'Generated hash fixtures',sourceIds:ids*.toString()],UUID.randomUUID().toString(),'operator',correlation) }
    Map job(Map created) { inventory.job(UUID.fromString(created.jobId)) }
    void until(Closure done) {
        for (int n=0;n<1200 && !done.call();n++) worker.runOnce()
        assertTrue(done.call() as boolean,'The durable worker did not reach the expected boundary.')
    }
    void finish(Map created) { until { InventoryStore.TERMINAL.contains(job(created).state) } }
    UUID entry(Map created,String name) { jdbc.queryForObject('SELECT e.id FROM scan_entry e JOIN file_location l ON l.id=e.location_id WHERE e.scan_id=? AND l.relative_path_bytes=?',UUID,UUID.fromString(created.scanId),name.bytes) }
    Map manual(Map created,UUID id,boolean force=false,String key=UUID.randomUUID().toString()) {
        hashes.create([scanId:created.scanId,observationIds:[id.toString()],forceRehash:force],key,'operator',correlation)
    }
    Map evidence(UUID id) { inventory.observation(id).hash }
    Map groups(Map created) { hashes.groups(UUID.fromString(created.scanId),null,null,100) }

    Map query(Map scan,Map filters=[:],Map options=[:]) { search.search(UUID.fromString(scan.scanId),[filters:filters]+options) }
    Map annotate(UUID entry,Map extra=[:]) {
        Map observation=inventory.observation(entry)
        annotations.save((UUID)observation.locationId,[observationId:entry.toString(),expectedVersion:'0',memo:'Location note',tagIds:[],reviewState:'UNREVIEWED']+extra,'operator',correlation)
    }
    Map freeze(Map scan,Map filters=[:]) {
        Map page=query(scan,filters)
        selections.freeze(UUID.fromString(scan.scanId),[query:[filters:filters],viewToken:page.viewToken],'operator',correlation)
    }

    Map known(String content='hello',Map extra=[:]) {
        [name:'Benign hello',memo:'Known fixture',tags:['sample'],sizeBytes:content.bytes.length.toString(),algorithm:'SHA-256',checksum:HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(content.bytes)),filename:'original.txt',filenameMatchMode:'ADVISORY',enabled:true]+extra
    }
    Map save(Map body=known()) { catalog.put(null,body,'operator',correlation) }
    Map revise(Map signature,Map extra=[:]) {
        Map body=known(extra.get('content','hello').toString(),[name:signature.name,memo:signature.memo,tags:signature.tags*.label,sizeBytes:signature.sizeBytes,checksum:signature.checksum,filename:signature.filename,filenameBytesBase64:signature.filenameBytesBase64,filenameMatchMode:signature.filenameMatchMode,enabled:signature.enabled])+extra
        body.remove('content'); body.expectedRevision=signature.revision
        catalog.put((UUID)signature.id,body,'operator',correlation)
    }
    Map checked(UUID id,UUID run=null) { signatures.observation(id,run,null,100) }
    void rematch(Map scan) { until { def r=jdbc.queryForMap('SELECT r.* FROM scan s JOIN signature_run r ON r.id=s.current_signature_run_id WHERE s.id=?',UUID.fromString(scan.scanId)); r.catalog_revision.toString()==catalog.revision().toString() } }
    Map stage(List records,String policy='REJECT_EXISTING_ID') { exchange.stage(store.json([schemaVersion:1,signatures:records]).bytes,'JSON',policy,'operator',correlation) }
    Map apply(Map staged,String key=UUID.randomUUID().toString()) { exchange.apply((UUID)staged.id,[expectedCatalogRevision:staged.catalogRevision],key,'operator',correlation) }

    @Test void exactMatchesKeepAllLabelsAndSeparateManualNotesAndHistory() {
        Files.writeString(fixture.resolve('renamed.txt'),'hello'); Files.writeString(fixture.resolve('original.txt'),'hello')
        Files.writeString(fixture.resolve('resemblance.txt'),'jello')
        Map advisory=save(), exact=save(known('hello',[name:'Exact basename',filenameMatchMode:'REQUIRED_EXACT'])), same=save(known('hello',[name:'Second label']))
        Map scan=create(); finish(scan)
        UUID renamed=entry(scan,'renamed.txt'), original=entry(scan,'original.txt')
        Map findings=checked(renamed)
        assertEquals('CHECKED_HASH',findings.checkStatus); assertEquals('MATCHED',findings.matchStatus)
        assertEquals(['Benign hello','Second label'] as Set,findings.items*.signature*.name as Set)
        assertTrue(findings.items.every { it.signature.revision=='1' && it.matchedAt && it.signature.memo=='Known fixture' && it.signature.tags*.label==['sample'] })
        assertEquals(3,checked(original).items.size())
        assertEquals('NO_MATCH_IN_CHECKED_CATALOG',checked(entry(scan,'resemblance.txt')).matchStatus)
        Map tag=annotations.tags('sample',null,10).items.first()
        annotate(renamed,[tagIds:[tag.id.toString()],memo:'Independent note'])
        assertEquals([renamed,original] as Set,query(scan,[signatureId:advisory.id.toString()]).items*.id as Set)
        assertEquals([renamed],query(scan,[tagIds:[tag.id.toString()]]).items*.id)
        assertEquals([renamed,original] as Set,query(scan,[tagIds:[tag.id.toString()],tagScope:'EFFECTIVE']).items*.id as Set)
        int reads=sources.bodyReads
        revise(advisory,[enabled:false]); revise(same,[enabled:false]); rematch(scan)
        assertEquals(reads,sources.bodyReads)
        assertEquals([],checked(renamed).items)
        assertEquals('Independent note',annotations.get((UUID)inventory.observation(renamed).locationId,renamed).memo)
        assertEquals(['sample'],annotations.get((UUID)inventory.observation(renamed).locationId,renamed).tags*.label)
        Map history=checked(renamed,(UUID)findings.runId)
        assertFalse(history.current); assertEquals(2,history.items.size()); assertTrue(history.items.every { !it.active })
    }
    @Test void defaultUniqueCoverageNeedsExplicitPreviewConsentAndIdempotentRead() {
        Files.writeString(fixture.resolve('only.txt'),'hello'); Files.writeString(fixture.resolve('other'),'unrelated')
        save(); Map scan=create(); finish(scan); UUID only=entry(scan,'only.txt')
        assertEquals(0,sources.bodyReads); assertEquals('HASH_REQUIRED',checked(only).checkStatus); assertEquals('UNDETERMINED',checked(only).matchStatus)
        assertEquals('EXCLUDED_BY_SIZE',checked(entry(scan,'other')).checkStatus)
        Map preview=signatures.preview(UUID.fromString(scan.scanId),'operator')
        assertEquals('1',preview.candidateFiles); assertEquals('5',preview.candidateBytes)
        assertThrows(JobProblem) { signatures.check([previewId:preview.id.toString(),allowBodyReads:false],'no','operator',correlation) }
        assertEquals(0,sources.bodyReads)
        Map body=[previewId:preview.id.toString(),allowBodyReads:true]
        Map check=signatures.check(body,'explicit','operator',correlation)
        assertEquals(check,signatures.check(body,'explicit','operator',correlation))
        finish(check)
        assertEquals(1,sources.bodyReads); assertEquals('MATCHED',checked(only).matchStatus)
        assertEquals('NOT_REQUESTED_UNIQUE_SIZE',evidence(entry(scan,'other')).status)
        assertEquals(['SIGNATURE_SIZE'],store.mapper.readValue(jdbc.queryForObject('SELECT reasons::text FROM hash_attempt WHERE entry_id=?',String,only),List))
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='SIGNATURE_CHECK_AUTHORIZED'",Integer))
    }
    @Test void scanOptionUsesCapturedSizeUnionAndOneTaskWithAllReasons() {
        Files.writeString(fixture.resolve('a'),'hello'); Files.writeString(fixture.resolve('b'),'hello'); Files.writeString(fixture.resolve('c'),'different'); Files.writeString(fixture.resolve('d'),'unrelated-size')
        save(); save(known('different'))
        Map scan=inventory.create([name:'Explicit size union',sourceIds:[sourceId.toString()],includeSignatureCandidates:true],'union','operator',correlation)
        save(known('unrelated-size')) // new catalog revision is not scan consent
        finish(scan)
        assertEquals(3,sources.bodyReads)
        assertEquals(['DUPLICATE_SIZE','SIGNATURE_SIZE'],store.mapper.readValue(jdbc.queryForObject('SELECT reasons::text FROM hash_attempt WHERE entry_id=?',String,entry(scan,'a')),List))
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM work_item WHERE entry_id=? AND kind='HASH'",Integer,entry(scan,'a')))
        assertEquals('HASH_REQUIRED',checked(entry(scan,'d')).checkStatus)
        assertEquals('NOT_REQUESTED_UNIQUE_SIZE',evidence(entry(scan,'d')).status)
    }
    @Test void catalogChangeMidBatchFinishesFrozenRevisionThenRematchesWithoutReads() {
        for (int n=0;n<105;n++) Files.writeString(fixture.resolve('file-'+n),'hello')
        Map old=save(); Map scan=create(); finish(scan); int reads=sources.bodyReads
        Map second=revise(old,[name:'Second revision'])
        worker.runOnce() // queue the background job
        worker.runOnce() // capture first 100 checks
        Map running=jdbc.queryForMap("SELECT * FROM signature_run WHERE catalog_revision=2 AND state='BUILDING'")
        revise(second,[enabled:false,name:'Disabled third revision'])
        until { jdbc.queryForObject("SELECT count(*) FROM signature_run WHERE id=? AND state='PUBLISHED'",Integer,running.id)==1 }
        Map historical=checked(entry(scan,'file-0'),(UUID)running.id)
        assertEquals('2',historical.catalogRevision); assertEquals('Second revision',historical.items.first().signature.name)
        rematch(scan); assertEquals([],checked(entry(scan,'file-0')).items)
        assertEquals(reads,sources.bodyReads)
    }
    @Test void createFromObservationRequiresAcceptedEvidenceAndPreservesRawBasename() {
        Files.writeString(fixture.resolve('a'),'hello'); Map scan=create(); finish(scan); UUID id=entry(scan,'a')
        Map body=[observationId:id.toString(),name:'From stored hash',memo:'Plain text',tags:[],filenameMatchMode:'REQUIRED_EXACT']
        assertEquals('HASH_REQUIRED',assertThrows(JobProblem) { save(body) }.code)
        assertEquals(0,sources.bodyReads); Map manual=manual(scan,id); finish(manual)
        // Raw-byte database fixture; native non-UTF-8 traversal is covered by the adapter tests.
        byte[] raw=[(byte)0xfb,(byte)0xff] as byte[]
        jdbc.update('UPDATE file_location SET name_bytes=?,display_name=? WHERE id=?',raw,'\\xfb\\xff',inventory.observation(id).locationId)
        Map signature=save(body)
        assertEquals('FROM_OBSERVATION',signature.origin); assertEquals(known().checksum,signature.checksum)
        assertArrayEquals(raw,Base64.decoder.decode(signature.filenameBytesBase64))
        rematch(scan); assertEquals('MATCHED',checked(id).matchStatus)
        assertEquals(1,sources.bodyReads)
        assertThrows(JobProblem) { save(body+[checksum:'0'*64]) }
    }
    @Test void jsonValidationIsWholeUploadAtomicAndVersionedApplyIsRetrySafe() {
        Map good=known(), bad=known('hello',[name:'Bad',checksum:'nonsense'])
        Map invalid=stage([good,bad]); assertEquals('INVALID',invalid.state); assertEquals(1,invalid.errorCount)
        assertThrows(JobProblem) { apply(invalid) }; assertEquals(0L,catalog.revision())
        assertEquals(0,jdbc.queryForObject('SELECT count(*) FROM signature',Integer))
        String id=UUID.randomUUID().toString()
        assertEquals('INVALID',stage([good+[id:id],good+[id:id]]).state)
        Map valid=stage([good+[id:id],good+[id:UUID.randomUUID().toString(),name:'Another label']])
        assertEquals(0,jdbc.queryForObject('SELECT count(*) FROM signature',Integer))
        Map applied=apply(valid,'apply-once'); assertEquals(applied,apply(valid,'apply-once'))
        assertEquals(2,jdbc.queryForObject('SELECT count(*) FROM signature',Integer)); assertEquals(1L,catalog.revision())
        assertEquals('INVALID',stage([good+[id:id,revision:'1']]).state)
        Map update=stage([good+[id:id,revision:'1',name:'Update']], 'UPDATE_BY_ID')
        save(known('extra'))
        assertEquals('CATALOG_CONFLICT',assertThrows(JobProblem) { apply(update) }.code)
        assertEquals('1',catalog.get(UUID.fromString(id)).revision)
        Map restaged=stage([good+[id:id,revision:1,name:'Update']], 'UPDATE_BY_ID')
        apply(restaged); assertEquals('2',catalog.get(UUID.fromString(id)).revision)
        assertEquals('INVALID',stage([good+[id:id,revision:1]],'UPDATE_BY_ID').state)
    }
    @Test void malformedFormatsExactNumbersAndConfiguredLimitsFailClosed() {
        for (Map bad : [[algorithm:'SHA-1'],[sizeBytes:'-1'],[sizeBytes:'9223372036854775808'],[sizeBytes:5],[checksum:'x'*64],[filenameBytesBase64:'%%%'],[schemaVersion:2],[enabled:'true']])
            assertEquals('INVALID',stage([known()+bad]).state,bad.toString())
        assertEquals('INVALID',exchange.stage('{"schemaVersion":1,"schemaVersion":2,"signatures":[]}'.bytes,'JSON','REJECT_EXISTING_ID','operator',correlation).state)
        assertEquals('INVALID',exchange.stage('name,name\na,b\n'.bytes,'CSV','REJECT_EXISTING_ID','operator',correlation).state)
        limits.importRows=1
        assertEquals(413,assertThrows(JobProblem) { stage([known(),known()]) }.status)
        limits.importBytes=32
        assertEquals(413,assertThrows(JobProblem) { exchange.bounded(new ByteArrayInputStream(new byte[33])) }.status)
        assertEquals(413,assertThrows(JobProblem) { stage([known()]) }.status)
        assertEquals(0L,catalog.revision())
    }
    @Test void csvParserAndDurableExportsFreezeMetadataQuoteCellsAndPublishOnlyCompleteBytes() {
        String memo='=1+1,\r\n"quoted"'
        Map signature=save(known('hello',[name:'\t +example',memo:memo,filenameBytesBase64:Base64.encoder.encodeToString([(byte)0xfb,(byte)0xff] as byte[])]))
        Map export=exports.create([format:'CSV',catalogRevision:'1'],'csv','operator',correlation)
        assertThrows(JobProblem) { exports.download((UUID)export.id,'operator',new ByteArrayOutputStream()) }
        worker.runOnce() // first chunk; no source scans
        exports.control((UUID)export.id,'pause','operator',correlation)
        worker.runOnce(); assertEquals('PAUSED',exports.get((UUID)export.id,'operator').state)
        revise(signature,[memo:'New revision'])
        exports.control((UUID)export.id,'resume','operator',correlation)
        until { exports.get((UUID)export.id,'operator').state=='READY' }
        ByteArrayOutputStream out=new ByteArrayOutputStream(); exports.download((UUID)export.id,'operator',out)
        String csv=out.toString('UTF-8'); assertTrue(csv.contains("'=1+1")); assertTrue(csv.contains("'\t +example"))
        Map info=exports.get((UUID)export.id,'operator')
        assertEquals(out.size().toString(),info.byteCount); assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(out.toByteArray())),info.sha256)
        // Exported existing IDs need an explicit update policy and their captured expected revisions.
        Map stale=exchange.stage(out.toByteArray(),'CSV','UPDATE_BY_ID','operator',correlation)
        assertEquals('INVALID',stale.state)
        String fresh=csv.replace(',1,',',2,')
        Map dry=exchange.stage(fresh.bytes,'CSV','UPDATE_BY_ID','operator',correlation)
        assertEquals('VALID',dry.state,dry.toString()); assertEquals(signature.filenameBytesBase64,dry.items.first().proposed.filenameBytesBase64)
        Map json=exports.create([format:'JSON',catalogRevision:'1'],'json','operator',correlation)
        until { exports.get((UUID)json.id,'operator').state=='READY' }
        ByteArrayOutputStream exact=new ByteArrayOutputStream(); exports.download((UUID)json.id,'operator',exact)
        Map envelope=store.parse(exact.toString('UTF-8'))
        assertEquals(memo,envelope.signatures.first().memo); assertEquals('1',envelope.signatures.first().revision)
        assertEquals(0,sources.bodyReads)
    }
    @Test void evidenceInvalidationHidesCurrentMatchesAndInterruptedMatchResumesDatabaseOnly() {
        Files.writeString(fixture.resolve('a'),'hello'); Files.writeString(fixture.resolve('b'),'hello')
        Map signature=save(); Map scan=create(); finish(scan); UUID a=entry(scan,'a')
        Map v2=revise(signature,[memo:'Updated'])
        worker.runOnce(); worker.runOnce()
        UUID jobId=jdbc.queryForObject("SELECT id FROM job WHERE type='SIGNATURE_MATCH' AND state='RUNNING'",UUID)
        inventory.control(jobId,'pause','operator',correlation); worker.runOnce()
        assertEquals('PAUSED',inventory.job(jobId).state)
        sources.failRoot=true // database-only resume must not validate or open sources
        inventory.control(jobId,'resume','operator',correlation); rematch(scan)
        assertEquals('MATCHED',checked(a).matchStatus)
        int reads=sources.bodyReads
        store.invalidateScanEvidence(UUID.fromString(scan.scanId),'SOURCE_CONFIGURATION_CHANGED')
        assertEquals('STALE',checked(a,(UUID)checked(a).runId).checkStatus); assertFalse(checked(a,(UUID)checked(a).runId).items.any { it.active });
        assertEquals('STALE',checked(a).checkStatus); assertEquals('UNDETERMINED',checked(a).matchStatus)
        assertTrue(checked(a).items.every { !it.active })
        assertEquals([],query(scan,[signatureStatus:'MATCHED']).items)
        assertEquals(reads,sources.bodyReads)
    }
    @Test void exportedQuotaAndFailureNeverExposePartialArtifacts() {
        save(known('hello',[memo:'x'*2000])); limits.exportBytes=1024; limits.exportQuotaBytes=1024
        Map first=exports.create([format:'JSON',catalogRevision:'1'],'bounded','operator',correlation)
        assertEquals('EXPORT_QUOTA',assertThrows(JobProblem) { exports.create([format:'CSV',catalogRevision:'1'],'other','operator',correlation) }.code)
        until { exports.get((UUID)first.id,'operator').state=='FAILED' }
        assertEquals('EXPORT_TOO_LARGE',exports.get((UUID)first.id,'operator').errorCode)
        assertEquals(0,jdbc.queryForObject('SELECT count(*) FROM signature_export_chunk WHERE export_id=?',Integer,first.id))
        assertThrows(JobProblem) { exports.download((UUID)first.id,'operator',new ByteArrayOutputStream()) }
        Map cancelled=exports.create([format:'JSON',catalogRevision:'0'],'empty','operator',correlation)
        exports.control((UUID)cancelled.id,'cancel','operator',correlation)
        worker.runOnce(); assertEquals('CANCELLED',exports.get((UUID)cancelled.id,'operator').state)
    }
    @Test void applyRollsBackEarlierRowsTagsClockAndRetryRecordOnDatabaseFailure() {
        Map staged=stage([known('hello',[name:'First',tags:['must roll back']]),known('world',[name:'Fail during apply'])])
        jdbc.execute("ALTER TABLE signature_revision ADD CONSTRAINT generated_import_failure CHECK (name<>'Fail during apply') NOT VALID")
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException) { apply(staged,'failed-apply') }
            assertEquals(0,jdbc.queryForObject('SELECT count(*) FROM signature',Integer))
            assertEquals(0,jdbc.queryForObject('SELECT count(*) FROM tag',Integer))
            assertEquals(0L,catalog.revision())
            assertEquals('VALID',exchange.get((UUID)staged.id,'operator',null,100).state)
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE request_key='failed-apply'",Integer))
        } finally { jdbc.execute('ALTER TABLE signature_revision DROP CONSTRAINT generated_import_failure') }
        apply(staged,'failed-apply'); assertEquals(2,jdbc.queryForObject('SELECT count(*) FROM signature',Integer))
    }
    @Test void renamedReusableTagsKeepTheirIdentityOnSignatureEdit() {
        Map old=save(); Map tag=old.tags.first()
        annotations.putTag(UUID.fromString(tag.id.toString()),[label:'renamed shared tag',expectedVersion:tag.version],'operator',correlation)
        Map body=known(); body.remove('tags'); body.tagIds=[tag.id.toString()]; body.expectedRevision=old.revision
        Map updated=catalog.put((UUID)old.id,body,'operator',correlation)
        assertEquals([tag.id.toString()],updated.tags*.id*.toString())
        assertEquals(['renamed shared tag'],updated.tags*.label)
        assertEquals(['sample'],catalog.get((UUID)old.id,1L).tags*.label)
    }

    @Test void frozenSignatureSelectionsBecomeReviewableOnCatalogOrPublishedFindingChanges() {
        Files.writeString(fixture.resolve('a'),'hello'); Files.writeString(fixture.resolve('b'),'hello')
        Map signature=save(); Map scan=create(); finish(scan)
        Map selected=freeze(scan,[signatureId:signature.id.toString()])
        assertFalse(selections.get((UUID)selected.id,'operator',null,100).needsReview)
        revise(signature,[enabled:false])
        assertTrue(selections.get((UUID)selected.id,'operator',null,100).needsReview)
        assertEquals('SELECTION_STALE',assertThrows(JobProblem) { selections.apply([selectionId:selected.id.toString(),reviewState:'KEEP'],'changed-findings','operator',correlation) }.code)
        assertEquals(0,jdbc.queryForObject('SELECT count(*) FROM file_annotation',Integer))
        rematch(scan); assertEquals('2',selections.get((UUID)selected.id,'operator',null,100).count)
    }

}
