package ca.fnord.dedup.inventory

import ca.fnord.dedup.roots.*
import ca.fnord.dedup.roots.fs.*
import com.sun.jna.Native
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.*
import java.util.concurrent.*
import static org.junit.jupiter.api.Assertions.*

/** PostgreSQL only: defaults to native PG/Testcontainers. External URLs must be disposable. */
@Tag('postgres')
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryIntegrationTest {
    PostgreSQLContainer database
    HikariDataSource pool
    JdbcTemplate jdbc
    TransactionTemplate tx
    InventoryStore store
    InventoryService service
    FixtureRegistry sources
    @TempDir Path fixture
    UUID sourceId
    final String correlation = UUID.randomUUID().toString()

    @BeforeAll void database() {
        String url = System.getenv('FNORD_TEST_DB_URL')
        if (!url) {
            database = new PostgreSQLContainer('postgres:18.6-bookworm@sha256:3725f4e2499eef5134592b3b4ab79a543ed7f8e533b05b5b637af926630f6650')
                .withDatabaseName('fnord').withUsername('fnord').withPassword('generated-disposable-test-password')
            database.start(); url = database.jdbcUrl
        }
        def ds = new DriverManagerDataSource(url,System.getenv('FNORD_TEST_DB_USER') ?: 'fnord',System.getenv('FNORD_TEST_DB_PASSWORD') ?: 'generated-disposable-test-password')
        new JdbcTemplate(ds).execute('CREATE SCHEMA IF NOT EXISTS inventory_test')
        pool = new HikariDataSource()
        pool.jdbcUrl = url
        pool.username = ds.username; pool.password = ds.password
        pool.maximumPoolSize = 2
        pool.connectionInitSql = 'SET search_path=inventory_test'
        ds = pool
        def flyway = Flyway.configure().dataSource(ds).schemas('inventory_test').defaultSchema('inventory_test')
        if (System.getenv('SPRING_FLYWAY_POSTGRESQL_TRANSACTIONAL_LOCK') == 'false') flyway.configuration(['flyway.postgresql.transactional.lock':'false'])
        flyway.load().migrate()
        jdbc = new JdbcTemplate(ds)
        tx = new TransactionTemplate(new JdbcTransactionManager(ds))
    }
    @AfterAll void shutdown() { pool?.close(); database?.stop() }
    @BeforeEach void reset() {
        jdbc.execute('TRUNCATE source_configuration,idempotency_record RESTART IDENTITY CASCADE')
        jdbc.execute("UPDATE scheduler_lock SET owner=NULL,token=0,expires_at='-infinity'")
        store = new InventoryStore(jdbc,tx,JsonMapper.builder().build())
        sources = new FixtureRegistry(store)
        sourceId = sources.add(fixture)
        service = new InventoryService(store,sources)
    }
    Map create(String key = UUID.randomUUID().toString(), List<UUID> ids = [sourceId]) {
        Map result=service.create([name:'Generated inventory',sourceIds:ids*.toString()],key,'operator',correlation)
        jdbc.update("UPDATE job SET type='INVENTORY' WHERE id=?",UUID.fromString(result.jobId))
        jdbc.update('UPDATE scan SET options=jsonb_set(options,?,?::jsonb) WHERE id=?','{inventoryOnly}', 'true',UUID.fromString(result.scanId))
        result
    }
    Map finish(Map created, InventoryStore target = store) {
        def worker = new InventoryWorker(target,sources)
        100.times {
            if (!InventoryStore.TERMINAL.contains(service.job(UUID.fromString(created.jobId)).state)) worker.runOnce()
        }
        service.scan(UUID.fromString(created.scanId))
    }
    Map job(Map created) { service.job(UUID.fromString(created.jobId)) }

    @Test void idleWorkerDoesNotAcquireSchedulerLeaseOrWriteHeartbeatState() {
        Map before = jdbc.queryForMap('SELECT owner,token,expires_at FROM scheduler_lock WHERE id=1')
        def worker = new InventoryWorker(store,sources)
        assertFalse(worker.runOnce())
        Map after = jdbc.queryForMap('SELECT owner,token,expires_at FROM scheduler_lock WHERE id=1')
        assertNull(after.owner)
        assertEquals(before.token,after.token)
        assertEquals(before.expires_at,after.expires_at)
    }

    @Test void nativeRecursiveInventoryHasExactMetadataAndNeverOpensBodies() {
        Files.createDirectories(fixture.resolve('nested/empty'))
        ['.hidden','zero','space name','line\nfeed','café','cafe\u0301','<img src=x onerror=alert(1)>'].each { Files.writeString(fixture.resolve(it),it == 'zero' ? '' : 'hello') }
        Files.writeString(fixture.resolve('nested/file.txt'),'hello')
        Files.createSymbolicLink(fixture.resolve('inside'),Path.of('nested'))
        Files.createSymbolicLink(fixture.resolve('outside'),Path.of('/etc/passwd'))
        Files.createSymbolicLink(fixture.resolve('loop'),Path.of('.'))
        def libc = Native.load('c',LinuxReadOnlyFileAccessTest.FixtureLibC)
        assertEquals(0,libc.mkfifo(fixture.resolve('fifo').toString(),0600))
        byte[] prefix = (fixture.toString()+'/').getBytes('UTF-8')
        byte[] full = Arrays.copyOf(prefix,prefix.length+3)
        full[prefix.length] = (byte)0xff; full[prefix.length+1] = (byte)0xfe
        int fd = libc.open(full,0x41|0x80,0600)
        assertTrue(fd >= 0); libc.close(fd)
        Path second = Files.createDirectory(fixture.resolveSibling(fixture.fileName.toString()+'-other'))
        try {
            Files.writeString(second.resolve('another'),'hello')
            UUID other = sources.add(second)
            def created = create(UUID.randomUUID().toString(),[sourceId,other])
            def result = finish(created)
            assertEquals('COMPLETED',result.job.state)
            assertTrue(result.sources.every { it.coverage == 'COMPLETE' })
            assertEquals(18,jdbc.queryForObject('SELECT count(*) FROM scan_entry',Integer))
            assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM scan_entry WHERE entry_type='SYMLINK'",Integer))
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM scan_entry WHERE entry_type='SPECIAL'",Integer))
            def raw = jdbc.queryForMap("SELECT e.* FROM scan_entry e JOIN file_location l ON l.id=e.location_id WHERE l.name_bytes=decode('fffe','hex')")
            def rendered = service.observation((UUID)raw.id)
            assertEquals('//4=',rendered.nameBytesBase64)
            assertEquals('[bytes] \\xff\\xfe',rendered.name)
            def file = jdbc.queryForMap("SELECT e.* FROM scan_entry e JOIN file_location l ON l.id=e.location_id WHERE l.display_path='nested/file.txt'")
            try (def root = sources.openValidated(sources.definition(sourceId))) {
                def actual = root.metadata('nested/file.txt'.bytes)
                assertEquals(actual.mtimeNanos,file.mtime_nanos)
                assertEquals(actual.ctimeNanos,file.ctime_nanos)
                assertEquals(Long.toUnsignedString(actual.inode),file.inode.toString())
            }
            assertEquals(0,sources.bodyReads)
            assertEquals('0',result.job.pendingWork)
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM work_item WHERE NOT subtree_resolved",Integer))
        } finally { Files.deleteIfExists(second.resolve('another')); Files.deleteIfExists(second) }
    }

    @Test void creationIsIdempotentAndRejectsUnsupportedOptionsAndQueueFlood() {
        def first = create('repeat')
        assertEquals(first,create('repeat'))
        assertEquals(1,jdbc.queryForObject('SELECT count(*) FROM scan',Integer))
        assertEquals('IDEMPOTENCY_CONFLICT',assertThrows(JobProblem) {
            service.create([name:'Different',sourceIds:[sourceId.toString()]],'repeat','operator',correlation)
        }.code)
        for (Map option : [[textIndexingEnabled:true],[includeSignatureCandidates:"true"],[hashAlgorithm:'SHA-1'],[containerPath:'/etc']]) {
            assertEquals('UNSUPPORTED_OPTION',assertThrows(JobProblem) {
                service.create([name:'Invalid',sourceIds:[sourceId.toString()]]+option,UUID.randomUUID().toString(),'operator',correlation)
            }.code)
        }
        4.times { create() }
        assertEquals(429,assertThrows(JobProblem) { create() }.status)
        assertEquals(first,create('repeat')) // Replay remains available at capacity.
    }

    @Test void pauseAfterCommittedBatchThenReplayKeepsCountersAndRows() {
        620.times { Files.writeString(fixture.resolve("f${it}"),'x') }
        def created = create()
        UUID jobId = UUID.fromString(created.jobId)
        def pausing = new AfterBatchStore(store)
        pausing.afterBatch = { entries ->
            if (entries.size() > 1) { pausing.afterBatch = null; service.control(jobId,'pause','operator',correlation) }
        }
        new InventoryWorker(pausing,sources).runOnce()
        assertEquals('PAUSED',job(created).state)
        long committed = Long.parseLong(job(created).discoveredEntries)
        assertTrue(committed > 1 && committed <= 621)
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM work_item WHERE state='LEASED'",Integer))
        def page = service.children(UUID.fromString(created.scanId),(UUID)jdbc.queryForObject('SELECT root_location_id FROM scan_source',UUID),null,10)
        assertEquals(10,page.items.size())
        service.control(jobId,'resume','operator',correlation)
        // Release the first scheduler to simulate its clean departure; no job is running now.
        jdbc.execute("UPDATE scheduler_lock SET expires_at='-infinity'")
        def result = finish(created)
        assertEquals('COMPLETED',result.job.state)
        assertEquals('621',result.job.discoveredEntries)
        assertEquals(621,jdbc.queryForObject('SELECT count(*) FROM scan_entry',Integer))
        assertEquals(1,jdbc.queryForObject('SELECT count(*) FROM work_item',Integer))
    }

    @Test void pausedScanCanBePromotedToUnsafeFastOnResume() {
        def created = create()
        UUID jobId = UUID.fromString(created.jobId)
        assertEquals('PAUSED',service.control(jobId,'pause','operator',correlation).state)
        assertEquals('QUEUED',service.control(jobId,'resume','operator',correlation,[unsafeFast:true]).state)
        Map scan = service.scan(UUID.fromString(created.scanId))
        assertTrue(scan.unsafeFast as boolean)
        Map snapshot = store.parse(jdbc.queryForObject('SELECT snapshot::text FROM scan_source WHERE scan_id=?',String,UUID.fromString(created.scanId)))
        assertEquals(Boolean.TRUE,snapshot.unsafeFast)
    }

    @Test void recoveryFencesOldWorkersAndRetainsFirstObservationOnConflict() {
        Files.writeString(fixture.resolve('file'),'hello')
        def created = create()
        UUID owner = UUID.randomUUID(), jobId = UUID.fromString(created.jobId)
        long epoch = store.acquire(owner)
        WorkClaim claim = store.claim(owner,epoch)
        def entry
        try (def root = sources.openValidated(sources.definition(sourceId))) {
            store.acceptRoot(claim,root.identity())
            store.batch(claim,[new InventoryEntry(path:new byte[0],name:new byte[0],metadata:root.identity())])
            entry = new InventoryEntry(parentId:claim.locationId,path:'file'.bytes,name:'file'.bytes,metadata:root.metadata('file'.bytes))
            store.batch(claim,[entry])
        }
        assertNull(store.acquire(UUID.randomUUID())) // A second scheduler cannot enter.
        jdbc.execute("UPDATE scheduler_lock SET expires_at='-infinity'")
        UUID replacement = UUID.randomUUID()
        long replacementEpoch = store.acquire(replacement)
        assertTrue(replacementEpoch > epoch)
        assertEquals('INTERRUPTED',job(created).state)
        assertThrows(LeaseLost) { store.batch(claim,[entry]) }
        Files.writeString(fixture.resolve('file'),'changed-length')
        service.control(jobId,'resume','operator',correlation)
        store.release(replacement,replacementEpoch)
        def result = finish(created)
        assertEquals('COMPLETED_WITH_ERRORS',result.job.state)
        assertEquals(2,jdbc.queryForObject('SELECT count(*) FROM scan_entry',Integer))
        def retained = jdbc.queryForMap("SELECT e.* FROM scan_entry e JOIN file_location l ON l.id=e.location_id WHERE l.display_name='file'")
        assertEquals(5L,retained.size_bytes)
        assertEquals(1,jdbc.queryForObject('SELECT count(*) FROM observation_validation WHERE entry_id=?',Integer,retained.id))
        assertTrue(service.observation((UUID)retained.id).unstable)
        assertEquals('PARTIAL',result.sources[0].coverage)
    }

    @Test void queuedPausedInterruptedAndRunningCancelAreDurableAndWinPauseRaces() {
        def created = create(); UUID id = UUID.fromString(created.jobId)
        assertEquals('PAUSED',service.control(id,'pause','operator',correlation).state)
        assertEquals('PAUSED',service.control(id,'pause','operator',correlation).state)
        assertEquals('CANCELLED',service.control(id,'cancel','operator',correlation).state)
        assertEquals('CANCELLED',service.control(id,'cancel','operator',correlation).state)
        assertThrows(JobProblem) { service.control(id,'resume','operator',correlation) }
        created = create(); id = UUID.fromString(created.jobId)
        UUID owner = UUID.randomUUID(); long epoch = store.acquire(owner)
        WorkClaim c = store.claim(owner,epoch)
        assertEquals('PAUSE_REQUESTED',service.control(id,'pause','operator',correlation).state)
        assertEquals('CANCEL_REQUESTED',service.control(id,'cancel','operator',correlation).state)
        assertEquals('CANCEL_REQUESTED',service.control(id,'pause','operator',correlation).state)
        store.checkpoint(c)
        assertEquals('CANCELLED',job(created).state)
        assertThrows(LeaseLost) { store.complete(c) }
        created = create(); c = store.claim(owner,epoch)
        service.control(UUID.fromString(created.jobId),'cancel','operator',correlation)
        jdbc.execute("UPDATE scheduler_lock SET expires_at='-infinity'")
        store.acquire(UUID.randomUUID())
        assertEquals('CANCELLED',job(created).state) // Startup completes pending cancellation.
    }

    @Test void resumeRejectsChangedConfigurationIdentityAvailabilityAndWritablePolicy() {
        def created = create(); UUID id = UUID.fromString(created.jobId)
        UUID owner = UUID.randomUUID(); long epoch = store.acquire(owner)
        def c = store.claim(owner,epoch)
        try (def root = sources.openValidated(sources.definition(sourceId))) { store.acceptRoot(c,root.identity()) }
        service.control(id,'pause','operator',correlation); store.checkpoint(c)
        String original = sources.revision
        sources.testRevision = 'f'*64
        assertEquals('SOURCE_CONFIGURATION_CHANGED',assertThrows(JobProblem) { service.control(id,'resume','operator',correlation) }.code)
        sources.testRevision = original
        for (String state : ['UNAVAILABLE','WRITABLE_SOURCE']) {
            sources.statusOverride = state
            assertEquals(state,assertThrows(JobProblem) { service.control(id,'resume','operator',correlation) }.code)
        }
        sources.statusOverride = null
        jdbc.execute("UPDATE scan_source SET root_identity=jsonb_set(root_identity,'{inode}','\"0\"')")
        assertEquals('SOURCE_CONFIGURATION_CHANGED',assertThrows(JobProblem) { service.control(id,'resume','operator',correlation) }.code)
        assertEquals('PAUSED',job(created).state)
    }

    @Test void metadataAndEnumerationFailuresLeavePartialCoverageAndOtherWorkContinues() {
        Files.createDirectories(fixture.resolve('denied/deeper'))
        Files.createDirectories(fixture.resolve('good/empty'))
        Files.writeString(fixture.resolve('good/visible'),'hello')
        Files.writeString(fixture.resolve('disappears'),'hello')
        sources.failList = 'denied'; sources.failMetadata = 'disappears'
        def created = create()
        def result = finish(created)
        assertEquals('COMPLETED_WITH_ERRORS',result.job.state)
        assertEquals('PARTIAL',result.sources[0].coverage)
        assertEquals('2',result.job.errorCount)
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM scan_entry e JOIN file_location l ON l.id=e.location_id WHERE l.display_path='good/visible'",Integer))
        assertEquals('UNKNOWN',jdbc.queryForObject("SELECT entry_type FROM scan_entry e JOIN file_location l ON l.id=e.location_id WHERE l.display_path='disappears'",String))
        assertEquals('COMPLETE',jdbc.queryForObject("SELECT directory_coverage FROM scan_entry e JOIN file_location l ON l.id=e.location_id WHERE l.display_path='good'",String))
        sources.statusOverride = 'UNAVAILABLE'; sources.failRoot = true
        jdbc.execute("UPDATE scheduler_lock SET expires_at='-infinity'")
        def unavailable = finish(create())
        assertEquals('UNAVAILABLE',unavailable.sources[0].coverage)
        assertTrue(jdbc.queryForObject('SELECT count(*) FROM scan_entry WHERE scan_id=?',Integer,UUID.fromString(created.scanId)) > 1)
    }

    @Test void finalBatchCrashReplaysWithoutDuplicatesAndPagingUsesCommittedSnapshot() {
        8.times { Files.writeString(fixture.resolve("f${it}"),'hello') }
        def created = create(); UUID id = UUID.fromString(created.jobId)
        def pausing = new AfterBatchStore(store)
        pausing.afterBatch = { entries -> if (entries.size() > 1) { pausing.afterBatch = null; service.control(id,'pause','operator',correlation) } }
        new InventoryWorker(pausing,sources).runOnce()
        assertEquals('PAUSED',job(created).state)
        UUID root = jdbc.queryForObject('SELECT root_location_id FROM scan_source',UUID)
        def first = service.children(UUID.fromString(created.scanId),root,null,3)
        assertNotNull(first.nextCursor)
        Files.writeString(fixture.resolve('late'),'new')
        service.control(id,'resume','operator',correlation)
        jdbc.execute("UPDATE scheduler_lock SET expires_at='-infinity'")
        finish(created)
        def names = first.items*.name
        def next = first.nextCursor
        while (next) {
            def page = service.children(UUID.fromString(created.scanId),root,next,3)
            names.addAll(page.items*.name); next = page.nextCursor
        }
        assertEquals(8,names.toSet().size())
        assertFalse(names.contains('late'))
        assertEquals(10,jdbc.queryForObject('SELECT count(*) FROM scan_entry',Integer))
        assertEquals('CURSOR_STALE',assertThrows(JobProblem) { service.scans(first.nextCursor,3) }.code)
        assertThrows(JobProblem) { service.control(id,'cancel','operator',correlation) }
        assertEquals(0,sources.bodyReads)
    }

    @Test void expiredWorkLeaseCannotBeRenewedOrCommitAndTransactionRollbackIsAtomic() {
        def created = create(); UUID owner = UUID.randomUUID(); long epoch = store.acquire(owner)
        def c = store.claim(owner,epoch)
        def valid = new InventoryEntry(parentId:c.locationId,path:'valid'.bytes,name:'valid'.bytes)
        def invalid = new InventoryEntry(parentId:c.locationId,path:'invalid'.bytes,name:new byte[256])
        assertThrows(Exception) { store.batch(c,[valid,invalid]) }
        assertEquals(0,jdbc.queryForObject('SELECT count(*) FROM scan_entry',Integer))
        assertEquals('0',job(created).discoveredEntries)
        jdbc.execute("UPDATE work_item SET lease_expires_at='-infinity' WHERE state='LEASED'")
        assertTrue(store.heartbeat(owner,epoch))
        assertThrows(LeaseLost) { store.batch(c,[valid]) }
        store.claim(owner,epoch)
        assertEquals('INTERRUPTED',job(created).state)
    }

    @Test void nativePostgresSerializesCompetingSchedulersAndCompletionAgainstCancel() {
        Assumptions.assumeTrue(System.getenv('FNORD_TEST_SQL_HARNESS') != 'pglite', 'Requires native PostgreSQL concurrent sessions; the SQL harness cannot certify locking.')
        def created = create()
        def executor = Executors.newFixedThreadPool(2)
        try {
            def start = new CountDownLatch(1)
            UUID a = UUID.randomUUID(), b = UUID.randomUUID()
            def claims = [a,b].collect { id -> executor.submit({ start.await(); [owner:id,epoch:store.acquire(id)] } as Callable<Map>) }
            start.countDown()
            def winners = claims.collect { it.get(10,TimeUnit.SECONDS) }.findAll { it.epoch != null }
            assertEquals(1,winners.size())
            def winner = winners.first()
            WorkClaim work = store.claim(winner.owner,(long)winner.epoch)
            store.complete(work)
            def race = new CountDownLatch(1)
            def completion = executor.submit({ race.await(); store.claim(winner.owner,(long)winner.epoch) } as Callable)
            def cancellation = executor.submit({
                race.await()
                try { service.control(work.jobId,'cancel','operator',correlation) }
                catch (JobProblem e) { assertEquals('INVALID_JOB_STATE',e.code) }
            } as Callable)
            race.countDown(); completion.get(10,TimeUnit.SECONDS); cancellation.get(10,TimeUnit.SECONDS)
            store.claim(winner.owner,(long)winner.epoch)
            assertTrue(job(created).state in ['COMPLETED','CANCELLED'])
        } finally { executor.shutdownNow() }
    }

    @Test void nativePostgresConnectionLossRollsBackTheEntireUnacknowledgedBatch() {
        Assumptions.assumeTrue(System.getenv('FNORD_TEST_SQL_HARNESS') != 'pglite', 'Requires native PostgreSQL process termination and separate sessions.')
        def created = create(); UUID owner = UUID.randomUUID(); long epoch = store.acquire(owner)
        WorkClaim work = store.claim(owner,epoch)
        def executor = Executors.newSingleThreadExecutor()
        try {
            assertThrows(Exception) {
                tx.executeWithoutResult {
                    store.batch(work,[new InventoryEntry(parentId:work.locationId,path:'unacknowledged'.bytes,name:'unacknowledged'.bytes)])
                    int pid = jdbc.queryForObject('SELECT pg_backend_pid()',Integer)
                    // Only the connection created by this disposable test is terminated.
                    executor.submit({ jdbc.queryForObject('SELECT pg_terminate_backend(?)',Boolean,pid) } as Callable<Boolean>).get(10,TimeUnit.SECONDS)
                }
            }
            assertEquals(0,jdbc.queryForObject('SELECT count(*) FROM scan_entry',Integer))
            assertEquals('0',job(created).discoveredEntries)
            assertEquals('RUNNING',job(created).state)
            jdbc.execute("UPDATE work_item SET lease_expires_at='-infinity' WHERE state='LEASED'")
            store.claim(owner,epoch)
            assertEquals('INTERRUPTED',job(created).state)
        } finally { executor.shutdownNow() }
    }

    static class AfterBatchStore extends InventoryStore {
        Closure afterBatch
        AfterBatchStore(InventoryStore original) { super(original.jdbc,original.tx,original.mapper) }
        @Override boolean batch(WorkClaim c,List<InventoryEntry> entries) {
            boolean result = super.batch(c,entries)
            afterBatch?.call(entries)
            result
        }
    }
    static class FixtureRegistry extends SourceRegistry {
        InventoryStore store
        Map<UUID,SourceDefinition> definitions = [:]
        Map<UUID,FileMetadata> identities = [:]
        ReadOnlyFileAccess nativeAccess = new LinuxReadOnlyFileAccess(new LinuxReadOnlyFileAccessTest.FixtureMountGuard())
        String testRevision = 'a'*64
        String statusOverride,failList,failMetadata
        boolean failRoot
        boolean allowBodyReads
        Closure readHook
        int bodyReads
        FixtureRegistry(InventoryStore store) {
            super(new SourceProperties(),null,store.jdbc,store.mapper)
            this.store = store
            store.jdbc.update('INSERT INTO source_configuration(revision,schema_version,document) VALUES (?,1,?::jsonb)',testRevision,'{}')
        }
        UUID add(Path path) {
            UUID id = UUID.randomUUID()
            definitions[id] = new SourceDefinition(id:id,sourceInstanceId:UUID.randomUUID(),key:'fixture-'+definitions.size(),label:'Generated fixture',containerPath:path.toString())
            refresh(); id
        }
        @Override void refresh() { definitions.each { id,s -> try (def root = nativeAccess.openRoot(s)) { identities[id] = root.identity() } } }
        @Override String getRevision() { testRevision }
        @Override SourceDefinition definition(UUID id) { definitions[id] }
        @Override FileMetadata identity(UUID id) { identities[id] }
        @Override List<SourceView> list() { definitions.values().collect { s -> new SourceView(s.id,s.sourceInstanceId,s.key,s.label,s.containerPath,true,false,s.unsafeFast,statusOverride ?: 'AVAILABLE','Generated test fixture',0) } }
        @Override ReadOnlyFileAccess.Root openValidated(SourceDefinition source) {
            if (failRoot) throw new SourceAccessException('UNAVAILABLE','Generated unavailable root.')
            def delegate = nativeAccess.openRoot(source)
            def registry = this
            new ReadOnlyFileAccess.Root() {
                @Override FileMetadata identity() { delegate.identity() }
                @Override FileMetadata metadata(byte[] path) {
                    if (new String(path,'UTF-8') == registry.failMetadata) throw new SourceAccessException('UNAVAILABLE','Generated metadata failure.')
                    delegate.metadata(path)
                }
                @Override ReadOnlyFileAccess.DirectoryCursor list(byte[] path) {
                    if (new String(path,'UTF-8') == registry.failList) throw new SourceAccessException('UNREADABLE','Generated enumeration failure.')
                    delegate.list(path)
                }
                @Override byte[] readLink(byte[] path) { delegate.readLink(path) }
                @Override ReadOnlyFileAccess.RegularFile openRegular(byte[] path,FileMetadata expected) {
                    registry.bodyReads++
                    if (!registry.allowBodyReads) throw new AssertionError('Inventory must never read source file bodies.')
                    def file=delegate.openRegular(path,expected)
                    new ReadOnlyFileAccess.RegularFile() {
                        @Override int read(byte[] buffer) { int count=file.read(buffer); registry.readHook?.call(path,buffer,count); count }
                        @Override FileMetadata metadata() { file.metadata() }
                        @Override boolean validateComplete() { file.validateComplete() }
                        @Override void close() { file.close() }
                    }
                }
                @Override void close() { delegate.close() }
            }
        }
    }
}
