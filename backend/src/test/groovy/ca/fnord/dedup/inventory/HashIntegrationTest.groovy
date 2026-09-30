package ca.fnord.dedup.inventory

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
class HashIntegrationTest {
    PostgreSQLContainer database
    HikariDataSource pool
    JdbcTemplate jdbc
    InventoryStore store
    InventoryService inventory
    HashService hashes
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
        new JdbcTemplate(ds).execute('CREATE SCHEMA IF NOT EXISTS hash_test')
        pool=new HikariDataSource(); pool.jdbcUrl=url; pool.username=ds.username; pool.password=ds.password; pool.maximumPoolSize=2
        pool.connectionInitSql='SET search_path=hash_test'
        def flyway=Flyway.configure().dataSource(pool).schemas('hash_test').defaultSchema('hash_test')
        if (System.getenv('SPRING_FLYWAY_POSTGRESQL_TRANSACTIONAL_LOCK')=='false') flyway.configuration(['flyway.postgresql.transactional.lock':'false'])
        flyway.load().migrate()
        jdbc=new JdbcTemplate(pool)
    }
    @AfterAll void close() { pool?.close(); database?.stop() }
    @BeforeEach void reset() {
        jdbc.execute('TRUNCATE source_configuration,idempotency_record RESTART IDENTITY CASCADE')
        jdbc.execute("UPDATE scheduler_lock SET owner=NULL,token=0,expires_at='-infinity'")
        store=new InventoryStore(jdbc,new TransactionTemplate(new JdbcTransactionManager(pool)),JsonMapper.builder().build())
        sources=new InventoryIntegrationTest.FixtureRegistry(store); sources.allowBodyReads=true; sourceId=sources.add(fixture)
        inventory=new InventoryService(store,sources); hashes=new HashService(store,inventory,new SelectionService(store,new SearchService(store,new AnnotationService(store,inventory)),new AnnotationService(store,inventory))); worker=new InventoryWorker(store,sources)
    }
    Map create(List ids=[sourceId]) { inventory.create([name:'Generated hash fixtures',sourceIds:ids*.toString()],UUID.randomUUID().toString(),'operator',correlation) }
    Map job(Map created) { inventory.job(UUID.fromString(created.jobId)) }
    void until(Closure done) {
        for (int n=0;n<1200 && !done.call();n++) worker.runOnce()
        assertTrue(done.call() as boolean,'The durable worker did not reach the expected boundary.')
    }
    void finish(Map created) { until { InventoryStore.TERMINAL.contains(job(created).state) } }
    UUID entry(Map created,String name) { jdbc.queryForObject('SELECT e.id FROM scan_entry e JOIN file_location l ON l.id=e.location_id WHERE e.scan_id=? AND l.display_path=?',UUID,UUID.fromString(created.scanId),name) }
    Map manual(Map created,UUID id,boolean force=false,String key=UUID.randomUUID().toString()) {
        hashes.create([scanId:created.scanId,observationIds:[id.toString()],forceRehash:force],key,'operator',correlation)
    }
    Map evidence(UUID id) { inventory.observation(id).hash }
    Map groups(Map created) { hashes.groups(UUID.fromString(created.scanId),null,null,100) }

    @Test void knownHashesCrossRootEmptyRenamedHardLinksAndUnequalContents() {
        Files.writeString(fixture.resolve('hello.txt'),'hello'); Files.createLink(fixture.resolve('renamed.bin'),fixture.resolve('hello.txt'))
        Files.writeString(fixture.resolve('other'),'world'); Files.writeString(fixture.resolve('unique'),'unique-size')
        Files.writeString(fixture.resolve('empty'),'')
        Path second=Files.createTempDirectory('fnord-m2-generated-')
        try {
            Files.writeString(second.resolve('hello-elsewhere'),'hello'); Files.writeString(second.resolve('empty-two'),'')
            Map scan=create([sourceId,sources.add(second)]); finish(scan)
            assertEquals('COMPLETED',job(scan).state)
            assertEquals(6,sources.bodyReads)
            assertEquals('NOT_REQUESTED_UNIQUE_SIZE',evidence(entry(scan,'unique')).status)
            def hello=evidence(entry(scan,'hello.txt')).accepted
            assertEquals('2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824',hello.digest)
            assertEquals('5',hello.bytesRead); assertEquals(['DUPLICATE_SIZE'],hello.reasons)
            assertNotNull(hello.preFingerprint); assertNotNull(hello.postFingerprint)
            assertTrue(hello.completedAt>=hello.startedAt)
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM hash_attempt WHERE octet_length(digest)<>32",Integer))
            def result=groups(scan); assertEquals('CURRENT',result.status); assertEquals(2,result.items.size())
            def group=result.items.find { it.sizeBytes=='5' }
            assertEquals('3',group.pathCount); assertEquals('2',group.objectCount); assertEquals('HARD_LINKS_PRESENT',group.identityStatus)
            assertEquals('5',group.maximumDuplicateCopyLogicalBytes); assertNull(group.physicalSavingsBytes)
            assertEquals(2,group.sourceIds.size()); assertEquals('HASH_IDENTICAL',group.evidenceLevel)
            def page=hashes.group((UUID)group.id,null,1)
            assertEquals(1,page.members.size()); assertNotNull(page.nextCursor)
            assertEquals(1,hashes.group((UUID)group.id,page.nextCursor,1).members.size())
            assertEquals('0',result.items.find { it.sizeBytes=='0' }.maximumDuplicateCopyLogicalBytes)
            assertEquals('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',result.items.find { it.sizeBytes=='0' }.digest)
        } finally { Files.deleteIfExists(second.resolve('hello-elsewhere')); Files.deleteIfExists(second.resolve('empty-two')); Files.delete(second) }
    }
    @Test void uniqueAndSoleEmptyStayUnhashedManualIsIdempotentAndReuseRetainsTimestamp() {
        Files.writeString(fixture.resolve('only'),'hello'); Files.writeString(fixture.resolve('empty'),'')
        Map scan=create(); finish(scan); assertEquals(0,sources.bodyReads)
        UUID id=entry(scan,'only')
        assertEquals('NOT_REQUESTED_UNIQUE_SIZE',evidence(entry(scan,'empty')).status)
        Map request=manual(scan,id,false,'manual-key'); assertEquals(request,manual(scan,id,false,'manual-key'))
        assertEquals('IDEMPOTENCY_CONFLICT',assertThrows(JobProblem) { manual(scan,id,true,'manual-key') }.code)
        finish(request); def accepted=evidence(id).accepted; assertEquals(['MANUAL'],accepted.reasons)
        Map reused=manual(scan,id); finish(reused)
        assertEquals(1,sources.bodyReads); assertEquals(accepted,evidence(id).accepted); assertEquals('1',job(reused).reusedFiles)
        assertEquals(1,hashes.attempts(id,null,20).items.size())
        assertEquals('0',job(reused).physicalBytesRead)
    }
    @ParameterizedTest @ValueSource(ints=[1,3]) void pauseRestartsAtZeroWithoutPartialDigest(int chunk) {
        byte[] content=new byte[5*1024*1024+3]; Arrays.fill(content,(byte)7)
        Files.write(fixture.resolve('a'),content); Files.write(fixture.resolve('b'),content)
        Map scan=create(); UUID jobId=UUID.fromString(scan.jobId); int reads=0
        sources.readHook={path,buffer,count -> if (count>0 && ++reads==chunk) { sources.readHook=null; inventory.control(jobId,'pause','operator',correlation) } }
        until { job(scan).state=='PAUSED' }
        assertEquals(0,jdbc.queryForObject('SELECT count(*) FROM hash_attempt',Integer))
        assertEquals('0',job(scan).physicalBytesRead)
        inventory.control(jobId,'resume','operator',correlation); finish(scan)
        assertEquals('COMPLETED',job(scan).state)
        assertEquals(Long.toString(2L*content.length),job(scan).usefulBytesHashed)
        assertEquals(Long.toString(2L*content.length),job(scan).physicalBytesRead)
        assertEquals(3,sources.bodyReads)
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(content)),groups(scan).items[0].digest)
    }
    @Test void cancelDiscardsIncompleteReadAndCannotResume() {
        Files.write(fixture.resolve('a'),new byte[2*1024*1024]); Files.write(fixture.resolve('b'),new byte[2*1024*1024])
        Map scan=create(); UUID id=UUID.fromString(scan.jobId)
        sources.readHook={path,buffer,count -> if (count>0) { sources.readHook=null; inventory.control(id,'cancel','operator',correlation) } }
        finish(scan); assertEquals('CANCELLED',job(scan).state)
        assertEquals(0,jdbc.queryForObject('SELECT count(*) FROM accepted_hash',Integer))
        assertEquals(0,jdbc.queryForObject('SELECT count(*) FROM hash_attempt',Integer))
        assertEquals('0',job(scan).physicalBytesRead)
        assertEquals('0',job(scan).usefulBytesHashed)
        assertEquals('INVALID_JOB_STATE',assertThrows(JobProblem) { inventory.control(id,'resume','operator',correlation) }.code)
    }
    @ParameterizedTest @ValueSource(strings=['modify','truncate','grow','replace','remove']) void changedFilesNeverAcceptWrongObservation(String mutation) {
        Files.write(fixture.resolve('a'),new byte[2*1024*1024]); Files.write(fixture.resolve('b'),new byte[2*1024*1024])
        Map scan=create()
        sources.readHook={path,buffer,count ->
            if (count<=0) return
            sources.readHook=null
            Path target=fixture.resolve(new String(path,'UTF-8'))
            switch (mutation) {
                case 'modify': byte[] changed=new byte[2*1024*1024]; changed[0]=1; Files.write(target,changed); break
                case 'truncate': Files.write(target,new byte[1]); break
                case 'grow': Files.write(target,new byte[1],StandardOpenOption.APPEND); break
                case 'replace': Files.delete(target); Files.write(target,new byte[2*1024*1024]); break
                case 'remove': Files.delete(target); break
            }
        }
        finish(scan)
        assertEquals('COMPLETED_WITH_ERRORS',job(scan).state)
        assertEquals(1,jdbc.queryForObject('SELECT count(*) FROM accepted_hash WHERE active',Integer))
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM hash_attempt WHERE outcome='ACCEPTED'",Integer))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM hash_attempt WHERE outcome IN ('READING','FAILED','INTERRUPTED','STOPPED')",Integer))
        assertEquals(0,groups(scan).items.size())
        assertEquals(2,jdbc.queryForObject('SELECT count(*) FROM scan_entry WHERE size_bytes=2097152',Integer))
    }
    @Test void forcedDisagreementInvalidatesPublishedGroupsBeforeRebuild() {
        Files.writeString(fixture.resolve('a'),'hello'); Files.writeString(fixture.resolve('b'),'hello')
        Map scan=create(); finish(scan); UUID id=entry(scan,'a'); def original=evidence(id).accepted
        Map old=groups(scan); UUID oldGroup=(UUID)old.items[0].id
        Map forced=manual(scan,id,true)
        sources.readHook={path,buffer,count -> if (count>0) buffer[0]=(byte)(buffer[0]^1) } // Simulated corrupt read; source is unchanged.
        until { evidence(id).status=='STALE' }
        sources.readHook=null
        assertEquals('STALE',hashes.group(oldGroup,null,100).group.evidenceLevel)
        assertEquals('NEEDS_REBUILD',groups(scan).status)
        assertEquals(original,evidence(id).accepted)
        assertEquals('CONFLICT',evidence(id).latestAttempt.outcome); assertNull(evidence(id).latestAttempt.digest)
        finish(forced); assertEquals('COMPLETED_WITH_ERRORS',job(forced).state); assertEquals(0,groups(scan).items.size())
        assertEquals('STALE',hashes.groups(UUID.fromString(scan.scanId),(UUID)old.analysisId,null,100).items[0].evidenceLevel)
    }
    @Test void interruptedAnalysisNeverPublishesMixedEvidenceAndHistoryStaysSeparate() {
        Files.writeString(fixture.resolve('a'),'hello'); Files.writeString(fixture.resolve('b'),'hello'); Files.writeString(fixture.resolve('unique'),'unique')
        Map scan=create()
        until { jdbc.queryForObject('SELECT count(*) FROM analysis_input',Integer)>0 }
        assertNull(groups(scan).analysisId)
        inventory.control(UUID.fromString(scan.jobId),'pause','operator',correlation); until { job(scan).state=='PAUSED' }
        Map manualJob=manual(scan,entry(scan,'unique')); finish(manualJob)
        UUID manualRevision=(UUID)groups(scan).analysisId
        inventory.control(UUID.fromString(scan.jobId),'resume','operator',correlation); finish(scan)
        assertNotEquals(manualRevision,groups(scan).analysisId)
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM analysis_revision WHERE state='ABANDONED'",Integer))
        assertEquals('CURRENT',groups(scan).status)
        Map second=create(); finish(second)
        assertEquals(5,sources.bodyReads) // 2 automatic + 1 manual + 2 independently read in the next scan.
        assertEquals('2',groups(second).items[0].pathCount)
        assertNotEquals(groups(scan).items[0].id,groups(second).items[0].id)
        assertEquals('NOT_REQUESTED_UNIQUE_SIZE',evidence(entry(second,'unique')).status)
    }
    @Test void boundedCandidateCaptureAndMemberBatchesSurviveGroupPause() {
        503.times { Files.write(fixture.resolve("empty-${it}"),new byte[0]) }
        Map scan=create()
        until { jdbc.queryForObject('SELECT count(*) FROM analysis_input WHERE group_id IS NOT NULL',Integer)==500 }
        assertNull(groups(scan).analysisId)
        inventory.control(UUID.fromString(scan.jobId),'pause','operator',correlation); until { job(scan).state=='PAUSED' }
        assertEquals(500,jdbc.queryForObject('SELECT count(*) FROM analysis_input WHERE group_id IS NOT NULL',Integer))
        inventory.control(UUID.fromString(scan.jobId),'resume','operator',correlation); finish(scan)
        assertEquals('503',job(scan).hashedFiles); assertEquals(503,sources.bodyReads)
        assertEquals('503',groups(scan).items[0].pathCount)
        assertEquals(503,jdbc.queryForObject('SELECT count(*) FROM analysis_input WHERE group_id IS NOT NULL',Integer))
        def group=groups(scan).items[0]
        assertEquals(500,hashes.group((UUID)group.id,null,500).members.size())
        String cursor=hashes.group((UUID)group.id,null,500).nextCursor
        assertEquals(3,hashes.group((UUID)group.id,cursor,500).members.size())
    }
    @Test void hardLinkOnlyGroupHasZeroDuplicateCopyBytesAndUnknownIdentityIsConservative() {
        Files.writeString(fixture.resolve('a'),'hello'); Files.createLink(fixture.resolve('b'),fixture.resolve('a'))
        Map scan=create(); finish(scan)
        assertEquals('1',groups(scan).items[0].objectCount)
        assertEquals('0',groups(scan).items[0].maximumDuplicateCopyLogicalBytes)
        assertEquals(2,sources.bodyReads)
        // Model a filesystem without a supported identity capability in a new observation window.
        Map second=create(); until { job(second).phase=='HASHING' }
        jdbc.update('UPDATE scan_entry SET filesystem_type=NULL WHERE scan_id=?',UUID.fromString(second.scanId))
        finish(second)
        assertNull(groups(second).items[0].objectCount)
        assertNull(groups(second).items[0].maximumDuplicateCopyLogicalBytes)
        assertEquals('IDENTITY_UNKNOWN',groups(second).items[0].identityStatus)
    }
    @Test void changedConfigurationInvalidatesAllEvidenceWithoutRewritingHistoricalAttempts() {
        Files.writeString(fixture.resolve('a'),'hello'); Files.writeString(fixture.resolve('b'),'hello')
        Map scan=create(); finish(scan); UUID id=entry(scan,'a'); def original=evidence(id).accepted
        Map forced=manual(scan,id,true)
        sources.testRevision='b'*64
        until { job(forced).state=='PAUSED' }
        assertEquals('SOURCE_CONFIGURATION_CHANGED',job(forced).blockCode)
        assertEquals('STALE',evidence(id).status)
        assertEquals('STALE',groups(scan).items[0].evidenceLevel)
        assertEquals(original,evidence(id).accepted)
        assertEquals(2,sources.bodyReads)
    }
    @Test void manualRequestsRejectMixedHistoryUnboundedSelectionsAndInvalidOptions() {
        Files.writeString(fixture.resolve('only'),'hello')
        Map first=create(); finish(first); Map second=create(); finish(second)
        UUID id=entry(first,'only')
        List<Map> invalid=[
            [scanId:second.scanId,observationIds:[id.toString()]],
            [scanId:first.scanId,observationIds:[id.toString(),id.toString()]],
            [scanId:first.scanId,observationIds:Collections.nCopies(501,id.toString())],
            [scanId:first.scanId,observationIds:[id.toString()],forceRehash:'true'],
            [scanId:first.scanId,observationIds:[id.toString()],path:'/etc/passwd']
        ]
        invalid.each { body -> assertEquals(422,assertThrows(JobProblem) { hashes.create(body,UUID.randomUUID().toString(),'operator',correlation) }.status) }
        assertEquals('INVALID_IDEMPOTENCY_KEY',assertThrows(JobProblem) { manual(first,id,false,'') }.code)
        assertEquals(0,sources.bodyReads)
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM job WHERE type='HASH'",Integer))
    }
    @Test void expiredHashClaimCannotPublishAndLeavesNoPartialAttempt() {
        Files.writeString(fixture.resolve('a'),'hello'); Files.writeString(fixture.resolve('b'),'hello')
        Map scan=create()
        until { job(scan).phase=='HASHING' }
        // Claim with the current scheduler identity without running its filesystem operation.
        Map coordinator=store.one('SELECT * FROM scheduler_lock WHERE id=1')
        WorkClaim claim=store.claim((UUID)coordinator.owner,((Number)coordinator.token).longValue())
        HashPipeline pipeline=new HashPipeline(store,sources); UUID attempt=UUID.randomUUID()
        jdbc.update("UPDATE work_item SET lease_expires_at='-infinity' WHERE id=?",claim.id)
        assertThrows(LeaseLost) {
            pipeline.finishAttempt(claim,attempt,'ACCEPTED',new byte[32],null,null,null,null,5L,java.time.Instant.now())
        }
        assertEquals(0,jdbc.queryForObject('SELECT count(*) FROM hash_attempt WHERE id=?',Integer,attempt))
        worker.runOnce(); assertEquals('INTERRUPTED',job(scan).state)
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM hash_attempt WHERE outcome='INTERRUPTED'",Integer))
        inventory.control(UUID.fromString(scan.jobId),'resume','operator',correlation); finish(scan)
        assertEquals('COMPLETED',job(scan).state); assertEquals(2,jdbc.queryForObject('SELECT count(*) FROM accepted_hash WHERE active',Integer))
    }
}
