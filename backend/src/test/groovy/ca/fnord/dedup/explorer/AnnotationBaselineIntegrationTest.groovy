package ca.fnord.dedup.explorer

import ca.fnord.dedup.inventory.*
import ca.fnord.dedup.roots.fs.SourceAccessException
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import static org.junit.jupiter.api.Assertions.*

/** Generated fixtures only. Exercises real failed hash attempts and the migrated views. */
@Tag('postgres')
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnnotationBaselineIntegrationTest {
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
            database=new PostgreSQLContainer('postgres:18.6-bookworm@sha256:3725f4e2499eef5134592b3b4ab79a543ed7f8e533b05b5b637af926630f6650')
                .withDatabaseName('fnord').withUsername('fnord').withPassword('generated-disposable-test-password')
            database.start(); url=database.jdbcUrl
        }
        def ds=new DriverManagerDataSource(url,System.getenv('FNORD_TEST_DB_USER') ?: 'fnord',System.getenv('FNORD_TEST_DB_PASSWORD') ?: 'generated-disposable-test-password')
        new JdbcTemplate(ds).execute('CREATE SCHEMA IF NOT EXISTS annotation_baseline_test')
        pool=new HikariDataSource(); pool.jdbcUrl=url; pool.username=ds.username; pool.password=ds.password; pool.maximumPoolSize=2
        pool.connectionInitSql='SET search_path=annotation_baseline_test'
        def flyway=Flyway.configure().dataSource(pool).schemas('annotation_baseline_test').defaultSchema('annotation_baseline_test')
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
        inventory=new InventoryService(store,sources); annotations=new AnnotationService(store,inventory)
        search=new SearchService(store,annotations); selections=new SelectionService(store,search,annotations)
        hashes=new HashService(store,inventory,selections); worker=new InventoryWorker(store,sources)
    }
    Map create() { inventory.create([name:'Generated annotation baseline fixtures',sourceIds:[sourceId.toString()]],UUID.randomUUID().toString(),'operator',correlation) }
    void finish(Map created) {
        UUID job=UUID.fromString(created.jobId.toString())
        for (int n=0;n<1200 && !InventoryStore.TERMINAL.contains(inventory.job(job).state);n++) worker.runOnce()
        assertTrue(InventoryStore.TERMINAL.contains(inventory.job(job).state),'The worker did not finish the fixture job.')
    }
    UUID entry(Map scan,String name='one') {
        jdbc.queryForObject('SELECT e.id FROM scan_entry e JOIN file_location l ON l.id=e.location_id WHERE e.scan_id=? AND l.relative_path_bytes=?',UUID,UUID.fromString(scan.scanId.toString()),name.bytes)
    }
    Map hash(Map scan,UUID entry,boolean force=false) {
        hashes.create([scanId:scan.scanId,observationIds:[entry.toString()],forceRehash:force],UUID.randomUUID().toString(),'operator',correlation)
    }
    Map query(Map scan,Map filters=[:],Map options=[:]) { search.search(UUID.fromString(scan.scanId.toString()),[filters:filters]+options) }
    Map freeze(Map scan,String name='one') {
        Map filters=[nameExact:name], page=query(scan,filters)
        selections.freeze(UUID.fromString(scan.scanId.toString()),[query:[filters:filters],viewToken:page.viewToken],'operator',correlation)
    }
    Map annotate(UUID entry,List tags=[]) {
        UUID location=(UUID)inventory.observation(entry).locationId
        annotations.save(location,[observationId:entry.toString(),expectedVersion:'0',memo:'Keep this location note',reviewState:'REVIEWED',tagIds:tags],'operator',correlation)
    }

    @ParameterizedTest
    @CsvSource(['READ_FAILED,false','READ_FAILED,true','UNREADABLE,false','UNREADABLE,true'])
    void failedOlderBaselineWarnsEvenWhenNewerEvidenceIsUnchanged(String code,boolean hashNewer) {
        Files.writeString(fixture.resolve('one'),'hello'); Files.writeString(fixture.resolve('two'),'other!')
        Map first=create(); finish(first); UUID baseline=entry(first)
        finish(hash(first,baseline))
        Map tag=annotations.putTag(null,[label:'manual-baseline'],'operator',correlation)
        Map saved=annotate(baseline,[tag.id.toString()])
        Map baselineAttempt=inventory.observation(baseline).hash.accepted
        Map second=create(); finish(second); UUID current=entry(second)
        if (hashNewer) finish(hash(second,current))
        String currentHashStatus=inventory.observation(current).hash.status
        assertFalse(annotations.get((UUID)saved.locationId,current).needsReview)
        Map frozen=freeze(second), unrelated=freeze(second,'two'), page=query(second,[:],[limit:1])
        assertNotNull(page.nextCursor)
        Map annotationRow=store.one('SELECT * FROM file_annotation WHERE location_id=?',saved.locationId)
        Map forced=hash(first,baseline,true)
        // Simulate a transient read error after opening the generated file, not a source change.
        sources.readHook={ path,buffer,count -> throw new SourceAccessException(code,'Generated transient read failure.',0,Math.max(count as int,0)) }
        try { finish(forced) } finally { sources.readHook=null }
        assertEquals('COMPLETED_WITH_ERRORS',inventory.job(UUID.fromString(forced.jobId.toString())).state)
        assertEquals('FAILED',inventory.observation(baseline).hash.latestAttempt.outcome)
        assertEquals(code,inventory.observation(baseline).hash.latestAttempt.errorCode)
        assertFalse(jdbc.queryForObject('SELECT active FROM accepted_hash WHERE entry_id=?',Boolean,baseline))
        assertEquals(0,jdbc.queryForObject('SELECT count(*) FROM observation_validation WHERE entry_id=?',Integer,baseline))
        assertNull(store.one('SELECT evidence_block_code FROM scan WHERE id=?',UUID.fromString(first.scanId.toString())).evidence_block_code)
        assertEquals(baselineAttempt,inventory.observation(baseline).hash.accepted)
        assertEquals(currentHashStatus,inventory.observation(current).hash.status)
        assertEquals(annotationRow,store.one('SELECT * FROM file_annotation WHERE location_id=?',saved.locationId))

        int reads=sources.bodyReads; sources.allowBodyReads=false; sources.failRoot=true
        Map note=annotations.get((UUID)saved.locationId,current)
        assertTrue(note.needsReview); assertEquals(saved.memo,note.memo)
        assertEquals(saved.tags,note.tags); assertEquals(saved.version,note.version)
        assertEquals([current],query(second,[annotationsNeedReview:true]).items*.id)
        assertEquals('CURSOR_STALE',assertThrows(JobProblem) { query(second,[:],[cursor:page.nextCursor]) }.code)
        Map selection=selections.get((UUID)frozen.id,'operator',null,100)
        assertTrue(selection.needsReview)
        assertFalse(selection.items.first().capturedAnnotation.needsReview)
        assertTrue(selection.items.first().annotation.needsReview)
        assertFalse(selections.get((UUID)unrelated.id,'operator',null,100).needsReview)
        assertEquals('SELECTION_STALE',assertThrows(JobProblem) {
            selections.apply([selectionId:frozen.id.toString(),reviewState:'KEEP'],UUID.randomUUID().toString(),'operator',correlation)
        }.code)
        // Exercise selection hash validation directly to avoid the unrelated five-jobs/minute limit.
        assertEquals('SELECTION_STALE',assertThrows(JobProblem) {
            store.tx.execute { tx ->
                store.one('SELECT id FROM annotation_clock WHERE id=1 FOR SHARE')
                store.one('SELECT id FROM scan WHERE id=? FOR UPDATE',UUID.fromString(second.scanId.toString()))
                selections.hashIds((UUID)frozen.id,'operator',UUID.fromString(second.scanId.toString()))
            }
        }.code)
        assertEquals(annotationRow,store.one('SELECT * FROM file_annotation WHERE location_id=?',saved.locationId))
        assertEquals(reads,sources.bodyReads)
        // Explicit review against the newer observation establishes a healthy new baseline.
        Map reviewed=annotations.save((UUID)saved.locationId,[observationId:current.toString(),expectedVersion:saved.version,
            memo:saved.memo,tagIds:[tag.id.toString()],reviewState:'REVIEWED'],'operator',correlation)
        assertFalse(reviewed.needsReview); assertEquals(current,reviewed.baselineObservationId)
        assertEquals(reads,sources.bodyReads)
    }

    @Test void healthySameContentRehashDoesNotInvalidateHistoricalAnnotationBaseline() {
        Files.writeString(fixture.resolve('one'),'hello'); Files.writeString(fixture.resolve('two'),'hello')
        Map first=create(); finish(first); UUID baseline=entry(first)
        Map saved=annotate(baseline); Object originalAttempt=inventory.observation(baseline).hash.accepted.id
        Map second=create(); finish(second); UUID current=entry(second); Map frozen=freeze(second)
        finish(hash(first,baseline,true))
        assertNotEquals(originalAttempt,inventory.observation(baseline).hash.accepted.id)
        assertTrue(jdbc.queryForObject('SELECT active FROM accepted_hash WHERE entry_id=?',Boolean,baseline))
        assertEquals(originalAttempt,store.one('SELECT baseline_attempt_id FROM file_annotation WHERE location_id=?',saved.locationId).baseline_attempt_id)
        assertFalse(annotations.get((UUID)saved.locationId,current).needsReview)
        assertFalse(selections.get((UUID)frozen.id,'operator',null,100).needsReview)
    }

    @Test void metadataOnlyAnnotationsDoNotRequireHashesAndRemainReadFree() {
        Files.writeString(fixture.resolve('one'),'unique')
        Map first=create(); finish(first); UUID baseline=entry(first); Map saved=annotate(baseline)
        Map second=create(); finish(second); UUID current=entry(second)
        assertNull(store.one('SELECT baseline_attempt_id FROM file_annotation WHERE location_id=?',saved.locationId).baseline_attempt_id)
        assertNull(store.one('SELECT entry_id FROM accepted_hash WHERE entry_id=?',baseline))
        sources.allowBodyReads=false; sources.failRoot=true
        assertFalse(annotations.get((UUID)saved.locationId,current).needsReview)
        assertFalse(selections.get((UUID)freeze(second).id,'operator',null,100).needsReview)
        assertEquals(0,sources.bodyReads)
    }
}
