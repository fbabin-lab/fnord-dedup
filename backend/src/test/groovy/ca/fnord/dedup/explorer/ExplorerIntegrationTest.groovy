package ca.fnord.dedup.explorer

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
class ExplorerIntegrationTest {
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
        new JdbcTemplate(ds).execute('CREATE SCHEMA IF NOT EXISTS explorer_test')
        pool=new HikariDataSource(); pool.jdbcUrl=url; pool.username=ds.username; pool.password=ds.password; pool.maximumPoolSize=2
        pool.connectionInitSql='SET search_path=explorer_test'
        def flyway=Flyway.configure().dataSource(pool).schemas('explorer_test').defaultSchema('explorer_test')
        if (System.getenv('SPRING_FLYWAY_POSTGRESQL_TRANSACTIONAL_LOCK')=='false') flyway.configuration(['flyway.postgresql.transactional.lock':'false'])
        flyway.load().migrate()
        jdbc=new JdbcTemplate(pool)
    }
    @AfterAll void close() { pool?.close(); database?.stop() }
    @BeforeEach void reset() {
        jdbc.execute('TRUNCATE source_configuration,idempotency_record,tag,audit_event RESTART IDENTITY CASCADE')
        jdbc.execute('UPDATE annotation_clock SET revision=0')
        jdbc.execute("UPDATE scheduler_lock SET owner=NULL,token=0,expires_at='-infinity'")
        store=new InventoryStore(jdbc,new TransactionTemplate(new JdbcTransactionManager(pool)),JsonMapper.builder().build())
        sources=new InventoryIntegrationTest.FixtureRegistry(store); sources.allowBodyReads=true; sourceId=sources.add(fixture)
        inventory=new InventoryService(store,sources); hashes=new HashService(store,inventory,new SelectionService(store,new SearchService(store,new AnnotationService(store,inventory)),new AnnotationService(store,inventory))); worker=new InventoryWorker(store,sources)
        annotations=new AnnotationService(store,inventory); search=new SearchService(store,annotations); selections=new SelectionService(store,search,annotations)
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

    @Test void combinedLiteralRawMetadataAndUtcFiltersNeverOpenSources() {
        Files.createDirectory(fixture.resolve('dir'))
        Files.writeString(fixture.resolve('dir/a%_\n.txt'),'hello')
        Files.writeString(fixture.resolve('dir/aXY.txt'),'hello')
        Files.writeString(fixture.resolve('solo'),'longer unique')
        Map scan=create(); finish(scan)
        UUID target=entry(scan,'dir/a%_\n.txt'), directory=entry(scan,'dir')
        UUID location=(UUID)inventory.observation(target).locationId
        UUID parent=(UUID)inventory.observation(directory).locationId
        Map tag=annotations.putTag(null,[label:'  Ｋｅｅｐ  '],'operator',correlation)
        annotate(target,[memo:'Literal %_ <script>alert(1)</script>',tagIds:[tag.id.toString()],reviewState:'KEEP'])
        jdbc.update('UPDATE scan_entry SET mtime_seconds=100,mtime_nanos=123456789 WHERE id=?',target)
        sources.failRoot=true; sources.allowBodyReads=false
        int reads=sources.bodyReads
        Map filters=[nameContains:'%_',pathContains:'dir/',extension:'txt',sourceId:sourceId.toString(),subtreeLocationId:parent.toString(),parentLocationId:parent.toString(),
            entryType:'REGULAR',minBytes:'5',maxBytes:'5',mtimeFrom:'1970-01-01T00:01:40.123456789Z',mtimeTo:'1970-01-01T00:01:40.123456790Z',
            checksumPrefix:'2CF24',hashStatus:'ACCEPTED',duplicateState:'DUPLICATE',tagIds:[tag.id.toString()],tagMode:'ALL',memoContains:'%_',reviewState:'KEEP',hasError:false,stale:false]
        assertEquals([target],query(scan,filters).items*.id)
        assertEquals([],query(scan,filters+[mtimeTo:'1970-01-01T00:01:40.123456789Z',mtimeFrom:'1970-01-01T00:01:40Z']).items)
        assertEquals([target],query(scan,[nameExact:'a%_\n.txt',pathExact:'dir/a%_\n.txt']).items*.id)
        assertEquals([target],query(scan,[pathBytesBase64:inventory.observation(target).relativePathBytesBase64]).items*.id)
        assertEquals([target],query(scan,[checksum:inventory.observation(target).hash.accepted.digest,nameContains:'%_']).items*.id)
        assertEquals(2,search.directory(UUID.fromString(scan.scanId),parent).breadcrumbs.size())
        assertEquals('Literal %_ <script>alert(1)</script>',annotations.get(location).memo)
        assertEquals(1,annotations.history(location,null,1).items.size())
        assertEquals(reads,sources.bodyReads)
        assertEquals('INVALID_REQUEST',assertThrows(JobProblem) { query(scan,[contentQuery:'future']) }.code)
        assertThrows(JobProblem) { query(scan,[minBytes:5]) }
        assertThrows(JobProblem) { query(scan,[mtimeFrom:'2026-01-01']) }
    }
    @Test void equalKeysAndNullsPageExactlyBothDirectionsAndRejectChangedInputs() {
        15.times { Files.writeString(fixture.resolve('file-'+it),'same') }
        Map scan=create(); finish(scan)
        jdbc.update("UPDATE scan_entry SET mtime_seconds=100,mtime_nanos=1 WHERE scan_id=?",UUID.fromString(scan.scanId))
        for (String sort : ['name','path','size','mtime','checksumTime']) for (String direction : ['ASC','DESC']) {
            Set<UUID> seen=[]; String cursor=null
            do {
                Map page=query(scan,[:],[sort:sort,direction:direction,limit:3,cursor:cursor])
                for (Map item : page.items) assertTrue(seen.add((UUID)item.id))
                cursor=page.nextCursor
            } while (cursor!=null)
            assertEquals(16,seen.size())
        }
        Map first=query(scan,[:],[limit:2]); assertNotNull(first.nextCursor)
        assertEquals('CURSOR_STALE',assertThrows(JobProblem) { query(scan,[entryType:'REGULAR'],[cursor:first.nextCursor]) }.code)
        annotate(entry(scan,'file-0'))
        assertEquals('CURSOR_STALE',assertThrows(JobProblem) { query(scan,[:],[cursor:first.nextCursor]) }.code)
        first=query(scan,[:],[limit:2]); jdbc.update('UPDATE scan SET evidence_revision=evidence_revision+1 WHERE id=?',UUID.fromString(scan.scanId))
        assertEquals('CURSOR_STALE',assertThrows(JobProblem) { query(scan,[:],[cursor:first.nextCursor]) }.code)
    }
    @Test void notesSurviveRescanWarnForReplacementAndNeverFollowRename() {
        Files.writeString(fixture.resolve('file'),'original')
        Map first=create(); finish(first); UUID original=entry(first,'file')
        Map saved=annotate(original,[memo:'<img src=x onerror=alert(1)>'])
        Map second=create(); finish(second); UUID again=entry(second,'file')
        assertEquals(saved.locationId,inventory.observation(again).locationId)
        assertFalse(annotations.get((UUID)saved.locationId,again).needsReview)
        Files.delete(fixture.resolve('file')); Files.writeString(fixture.resolve('file'),'replacement content')
        Map third=create(); finish(third); UUID changed=entry(third,'file')
        assertTrue(annotations.get((UUID)saved.locationId,changed).needsReview)
        assertEquals(saved.memo,annotations.get((UUID)saved.locationId,changed).memo)
        Files.move(fixture.resolve('file'),fixture.resolve('renamed'))
        Map fourth=create(); finish(fourth)
        assertEquals('',annotations.get((UUID)inventory.observation(entry(fourth,'renamed')).locationId).memo)
        assertEquals(3,annotations.history((UUID)saved.locationId,null,100).items.size())
    }
    @Test void optimisticConcurrencyTagNormalizationLimitsAndAudit() {
        Files.writeString(fixture.resolve('file'),'one')
        Map scan=create(); finish(scan); UUID id=entry(scan,'file')
        Map saved=annotate(id)
        assertEquals('ANNOTATION_CONFLICT',assertThrows(JobProblem) { annotate(id,[memo:'stale tab']) }.code)
        assertEquals('Location note',annotations.get((UUID)saved.locationId).memo)
        assertThrows(JobProblem) { annotate(id,[expectedVersion:'1',memo:'x'*20001]) }
        Map tag=annotations.putTag(null,[label:'  Ｋｅｅｐ  '],'operator',correlation)
        assertEquals('Ｋｅｅｐ',tag.label)
        assertEquals('TAG_EXISTS',assertThrows(JobProblem) { annotations.putTag(null,[label:'keep'],'operator',correlation) }.code)
        assertEquals(1,annotations.tags('KEEP',null,10).items.size())
        annotations.putTag((UUID)tag.id,[label:'Keep renamed',expectedVersion:'1'],'operator',correlation)
        assertEquals('TAG_CONFLICT',assertThrows(JobProblem) { annotations.putTag((UUID)tag.id,[label:'old tab',expectedVersion:'1'],'operator',correlation) }.code)
        assertThrows(JobProblem) { annotations.putTag(null,[label:'x'*65],'operator',correlation) }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='ANNOTATION_UPDATED'",Integer))
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action IN ('TAG_CREATED','TAG_RENAMED')",Integer))
    }
    @Test void frozenBulkIsAtomicAuditedActorBoundAndIdempotent() {
        Files.writeString(fixture.resolve('a'),'same'); Files.writeString(fixture.resolve('b'),'same')
        Map scan=create(); finish(scan); Map tag=annotations.putTag(null,[label:'review'],'operator',correlation)
        Map frozen=freeze(scan,[entryType:'REGULAR']); UUID selection=(UUID)frozen.id
        assertEquals('2',frozen.count)
        assertEquals('SELECTION_NOT_FOUND',assertThrows(JobProblem) { selections.get(selection,'different-actor',null,100) }.code)
        assertNotNull(selections.get(selection,'operator',null,1).nextCursor)
        annotate(entry(scan,'b'))
        assertEquals('SELECTION_STALE',assertThrows(JobProblem) { selections.apply([selectionId:selection.toString(),reviewState:'KEEP'],'batch','operator',correlation) }.code)
        assertEquals('0',annotations.get((UUID)inventory.observation(entry(scan,'a')).locationId).version)
        assertTrue(selections.get(selection,'operator',null,100).needsReview)
        Map fresh=freeze(scan,[entryType:'REGULAR'])
        Map body=[selectionId:fresh.id.toString(),addTagIds:[tag.id.toString()],reviewState:'KEEP']
        Map result=selections.apply(body,'batch2','operator',correlation)
        assertEquals('2',result.updatedCount)
        assertEquals(result,selections.apply(body,'batch2','operator',correlation))
        assertEquals('SELECTION_APPLIED',assertThrows(JobProblem) { selections.apply(body,'new-key','operator',correlation) }.code)
        assertEquals(2,query(scan,[reviewState:'KEEP',tagIds:[tag.id.toString()],tagMode:'ANY']).items.size())
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='ANNOTATION_BATCH_ITEM'",Integer))
    }
    @Test void growingInventoryDoesNotExpandFrozenIdsAndLargeSumsAreExact() {
        Files.writeString(fixture.resolve('a'),'one'); Files.writeString(fixture.resolve('b'),'two-plus')
        Map scan=create(); finish(scan); UUID sid=UUID.fromString(scan.scanId)
        // Deliberately synthetic database values exercise arithmetic without allocating giant source files.
        jdbc.update("UPDATE scan_entry SET size_bytes=9223372036854775807 WHERE scan_id=? AND entry_type='REGULAR'",sid)
        Map page=query(scan,[entryType:'REGULAR'],[limit:1,sort:'size'])
        assertEquals('9223372036854775807',page.items[0].sizeBytes)
        Map frozen=freeze(scan,[entryType:'REGULAR'])
        assertEquals('18446744073709551614',frozen.totalBytes)
        Map source=store.one("SELECT * FROM scan_entry WHERE id=?",entry(scan,'a'))
        Map location=store.one('SELECT * FROM file_location WHERE id=?',source.location_id)
        UUID lid=UUID.randomUUID()
        jdbc.update('INSERT INTO file_location(id,source_id,source_instance_id,parent_id,name_bytes,relative_path_bytes,display_name,display_path) VALUES (?,?,?,?,?,?,?,?)',lid,sourceId,source.source_instance_id,location.parent_id,'new'.bytes,'new'.bytes,'new','new')
        jdbc.update("INSERT INTO scan_entry(id,scan_id,location_id,source_id,source_instance_id,entry_type,size_bytes,discovery_status,fingerprint,observed_at) VALUES (?,?,?,?,?,'REGULAR',5,'OBSERVED','{}',clock_timestamp())",UUID.randomUUID(),sid,lid,sourceId,source.source_instance_id)
        assertEquals(1,query(scan,[entryType:'REGULAR'],[sort:'size',cursor:page.nextCursor]).items.size())
        selections.apply([selectionId:frozen.id.toString(),reviewState:'REVIEWED'],null,'operator',correlation)
        assertEquals('0',annotations.get(lid).version)
        assertEquals(2,query(scan,[reviewState:'REVIEWED']).items.size())
    }
    @Test void tagRenamesEvidenceChangesAndExpiryInvalidateFrozenReview() {
        Files.writeString(fixture.resolve('file'),'one')
        Map scan=create(); finish(scan); UUID id=entry(scan,'file')
        Map tag=annotations.putTag(null,[label:'initial'],'operator',correlation)
        annotate(id,[tagIds:[tag.id.toString()]])
        Map frozen=freeze(scan,[entryType:'REGULAR'])
        annotations.putTag((UUID)tag.id,[label:'changed',expectedVersion:'1'],'operator',correlation)
        assertTrue(selections.get((UUID)frozen.id,'operator',null,100).needsReview)
        Map another=freeze(scan,[entryType:'REGULAR'])
        store.invalidateScanEvidence(UUID.fromString(scan.scanId),'FIXTURE_CHANGED')
        assertEquals('SELECTION_STALE',assertThrows(JobProblem) { selections.apply([selectionId:another.id.toString(),reviewState:'KEEP'],null,'operator',correlation) }.code)
        assertEquals(2,query(scan,[stale:true]).items.size())
        assertTrue(annotations.get((UUID)inventory.observation(id).locationId).needsReview)
        Map expired=freeze(scan,[entryType:'REGULAR'])
        jdbc.update("UPDATE selection SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",expired.id)
        assertEquals('SELECTION_EXPIRED',assertThrows(JobProblem) { selections.apply([selectionId:expired.id.toString(),reviewState:'KEEP'],null,'operator',correlation) }.code)
    }
    @Test void boundedSelectionRefusesTruncationAndCandidateStatusWaitsForMaterialization() {
        501.times { Files.writeString(fixture.resolve('file-'+it),'x'*(it+1)) }
        Map scan=create()
        until { store.one('SELECT inventory_frozen_at FROM scan WHERE id=?',UUID.fromString(scan.scanId)).inventory_frozen_at!=null }
        assertEquals('NOT_REQUESTED',inventory.observation(entry(scan,'file-0')).hash.status)
        finish(scan)
        assertEquals('NOT_REQUESTED_UNIQUE_SIZE',inventory.observation(entry(scan,'file-0')).hash.status)
        assertEquals('SELECTION_TOO_LARGE',assertThrows(JobProblem) { freeze(scan,[entryType:'REGULAR']) }.code)
        assertEquals(0,jdbc.queryForObject('SELECT count(*) FROM selection',Integer))
        assertEquals('500',freeze(scan,[entryType:'REGULAR',maxBytes:'500']).count)
    }
    @Test void frozenSelectionSchedulesHashesAndPreservesLegacyRetryFormat() {
        Files.writeString(fixture.resolve('only'),'unique')
        Map scan=create(); finish(scan); UUID id=entry(scan,'only')
        Map frozen=freeze(scan,[entryType:'REGULAR'])
        Map body=[scanId:scan.scanId,selectionId:frozen.id.toString(),forceRehash:false]
        Map request=hashes.create(body,'frozen-hash','operator',correlation); finish(request)
        assertEquals('ACCEPTED',evidence(id).status)
        assertEquals(request,hashes.create(body,'frozen-hash','operator',correlation))
        assertEquals('SELECTION_STALE',assertThrows(JobProblem) { hashes.create(body,'different','operator',correlation) }.code)
        assertEquals('INVALID_SELECTION',assertThrows(JobProblem) { hashes.create(body+[observationIds:[id.toString()]],null,'operator',correlation) }.code)
        Map legacy=manual(scan,id)
        String payload=Values.hash(store.json([scanId:UUID.fromString(scan.scanId),observationIds:[id],forceRehash:false]))
        jdbc.update("INSERT INTO idempotency_record(actor,endpoint,request_key,payload_hash,response) VALUES ('operator','/hash-jobs','pre-m3-key',?,?::jsonb)",payload,store.json(legacy))
        assertEquals(legacy,manual(scan,id,false,'pre-m3-key'))
        assertEquals('0',frozen.unknownSizes)
    }
    @Test void rawNonUtf8IdentityAndAnyAllTagsStayDistinct() {
        Files.writeString(fixture.resolve('one'),'first'); Files.writeString(fixture.resolve('two'),'second')
        Map scan=create(); finish(scan); UUID a=entry(scan,'one'),b=entry(scan,'two')
        byte[] raw=[(byte)0xff,(byte)0x25,(byte)0x5f] as byte[]
        jdbc.update('UPDATE file_location SET name_bytes=?,relative_path_bytes=?,display_name=?,display_path=? WHERE id=?',raw,raw,'\\xff%_','\\xff%_',inventory.observation(a).locationId)
        assertEquals([a],query(scan,[nameBytesBase64:Base64.encoder.encodeToString(raw)]).items*.id)
        assertEquals([],query(scan,[nameExact:'\\xff%_']).items)
        Map t1=annotations.putTag(null,[label:'<img src=x onerror=alert(1)>'],'operator',correlation),t2=annotations.putTag(null,[label:'second'],'operator',correlation)
        annotate(a,[tagIds:[t1.id.toString(),t2.id.toString()]]); annotate(b,[tagIds:[t2.id.toString()]])
        List ids=[t1.id.toString(),t2.id.toString()]
        assertEquals(2,query(scan,[tagIds:ids,tagMode:'ANY']).items.size())
        assertEquals([a],query(scan,[tagIds:ids,tagMode:'ALL']).items*.id)
    }

    @Test void invalidatedOlderBaselineExpiresNewerScanCursorAndFrozenReview() {
        Files.writeString(fixture.resolve('one'),'first'); Files.writeString(fixture.resolve('two'),'other')
        Map first=create(); finish(first); annotate(entry(first,'one'))
        Map second=create(); finish(second)
        Map page=query(second,[:],[limit:1]); Map frozen=freeze(second,[entryType:'REGULAR'])
        assertFalse(selections.get((UUID)frozen.id,'operator',null,100).needsReview)
        store.invalidateScanEvidence(UUID.fromString(first.scanId),'OLD_BASELINE_CHANGED')
        assertEquals('CURSOR_STALE',assertThrows(JobProblem) { query(second,[:],[cursor:page.nextCursor]) }.code)
        Map preview=selections.get((UUID)frozen.id,'operator',null,100)
        assertTrue(preview.needsReview)
        assertFalse(preview.items.find { it.id==entry(second,'one') }.capturedAnnotation.needsReview)
        assertTrue(preview.items.find { it.id==entry(second,'one') }.annotation.needsReview)
        assertEquals('SELECTION_STALE',assertThrows(JobProblem) { selections.apply([selectionId:frozen.id.toString(),reviewState:'KEEP'],null,'operator',correlation) }.code)
    }

}
