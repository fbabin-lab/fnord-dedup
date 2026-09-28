package ca.fnord.dedup.inventory

import ca.fnord.dedup.roots.*
import ca.fnord.dedup.roots.fs.*
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.*

@CompileStatic
@Component
@ConditionalOnProperty(prefix='fnord.inventory',name='enabled',havingValue='true',matchIfMissing=true)
class InventoryWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(InventoryWorker)
    final InventoryStore store
    final SourceRegistry sources
    final UUID owner = UUID.randomUUID()
    private volatile Long epoch
    private volatile boolean running
    private volatile boolean stopping
    private ScheduledExecutorService executor
    private ScheduledExecutorService heartbeats
    private UUID preparedJob

    InventoryWorker(InventoryStore store, SourceRegistry sources) { this.store = store; this.sources = sources }
    @Override boolean isRunning() { running }
    @Override int getPhase() { Integer.MAX_VALUE - 100 }
    @Override void start() {
        if (running) return
        running = true; stopping = false
        executor = Executors.newSingleThreadScheduledExecutor({ Runnable r -> Thread.ofPlatform().daemon().name('inventory-worker').unstarted(r) } as ThreadFactory)
        heartbeats = Executors.newSingleThreadScheduledExecutor({ Runnable r -> Thread.ofPlatform().daemon().name('inventory-heartbeat').unstarted(r) } as ThreadFactory)
        executor.scheduleWithFixedDelay({ tick() } as Runnable,0L,250L,TimeUnit.MILLISECONDS)
        heartbeats.scheduleWithFixedDelay({ renew() } as Runnable,10L,10L,TimeUnit.SECONDS)
    }
    private void renew() {
        Long current = epoch
        if (current == null) return
        try { if (!store.heartbeat(owner,current)) { epoch = null; preparedJob = null } }
        catch (Exception ignored) { LOG.warn('Inventory heartbeat could not reach the database; expired leases cannot commit.') }
    }
    private void tick() {
        if (stopping) return
        try { runOnce() }
        catch (LeaseLost ignored) { epoch = null; preparedJob = null }
        catch (Exception e) {
            // A failed/ambiguous DB commit is recovered through its lease. Never fabricate completion.
            LOG.error('Inventory iteration failed; durable work remains recoverable. Error type: {}',e.class.simpleName)
            epoch = null; preparedJob = null
        }
    }
    /** Also used by deterministic integration tests; production invocation is server-owned. */
    void runOnce() {
        if (epoch == null) epoch = store.acquire(owner)
        if (epoch == null) return
        WorkClaim claim = store.claim(owner,epoch)
        if (claim == null) { preparedJob = null; return }
        try {
            if (claim.configurationRevision != sources.revision) throw new JobProblem(409,'SOURCE_CONFIGURATION_CHANGED','Source configuration changed.')
            if (preparedJob != claim.jobId) { prepare(claim); preparedJob = claim.jobId }
            inventory(claim)
        } catch (JobProblem e) { store.block(claim,e.code); preparedJob = null }
    }
    private void prepare(WorkClaim c) {
        sources.refresh()
        // Capture all available root identities before starting any subtree. The set is bounded to 100.
        for (Map row : store.jdbc.queryForList('SELECT source_id FROM scan_source WHERE scan_id=?',c.scanId)) {
            UUID sourceId = (UUID)row.source_id
            SourceView view = sources.list().find { SourceView v -> v.id == sourceId }
            if (view == null) throw new JobProblem(409,'SOURCE_CONFIGURATION_CHANGED','A selected source is no longer configured.')
            if (view.status in ['SOURCE_OVERLAP','APPLICATION_STORAGE_OVERLAP','WRITABLE_SOURCE','UNSUPPORTED_PLATFORM','DISABLED'])
                throw new JobProblem(409,view.status,'A selected source failed source-safety validation.')
            if (view.status == 'AVAILABLE') {
                WorkClaim rootClaim = new WorkClaim(id:c.id,jobId:c.jobId,scanId:c.scanId,sourceId:sourceId,owner:c.owner,schedulerToken:c.schedulerToken,token:c.token)
                store.acceptRoot(rootClaim,sources.identity(sourceId))
            }
        }
    }
    private void controlBoundary(WorkClaim c) {
        if (stopping || store.shouldStop(c)) throw new InventoryStop()
    }
    private void validateBinding(WorkClaim c, SourceDefinition source, FileMetadata original) {
        try (ReadOnlyFileAccess.Root current = sources.openValidated(source)) {
            if (!original.sameObject(current.identity())) throw new JobProblem(409,'SOURCE_CONFIGURATION_CHANGED','The source binding changed during inventory.')
            store.acceptRoot(c,current.identity())
        }
    }
    private void inventory(WorkClaim c) {
        if (stopping || store.shouldStop(c)) { store.checkpoint(c); return }
        SourceDefinition source = store.mapper.readValue(c.sourceSnapshot,SourceDefinition)
        List<InventoryEntry> batch = new ArrayList<>()
        try (ReadOnlyFileAccess.Root root = sources.openValidated(source)) {
            store.acceptRoot(c,root.identity())
            MountTable mounts = MountTable.current()
            InventoryEntry directory = observe(root,c.path,null,new byte[0],source,mounts)
            Map original = store.one('SELECT fingerprint @> ?::jsonb AS matches FROM scan_entry WHERE scan_id=? AND location_id=?',
                store.json(directory.metadata == null ? [type:'DIRECTORY'] : InventoryEntry.identity(directory.metadata) + ([type:'DIRECTORY'] as Map<String,Object>)),c.scanId,c.locationId)
            store.batch(c,List.of(directory))
            if (directory.errorCode != null || directory.type() != 'DIRECTORY' || (original != null && original.matches != Boolean.TRUE)) {
                throw new SourceAccessException(directory.errorCode ?: 'CHANGED',directory.errorDetail ?: 'The directory no longer refers to the observed object.')
            }
            controlBoundary(c)
            long lastCommit = System.nanoTime()
            try (ReadOnlyFileAccess.DirectoryCursor cursor = root.list(c.path)) {
                while (true) {
                    byte[] name = cursor.next()
                    if (name == null) break
                    byte[] path = append(c.path,name)
                    batch.add(observe(root,path,c.locationId,name,source,mounts))
                    if (batch.size() >= 500 || System.nanoTime()-lastCommit >= 1_000_000_000L) {
                        store.batch(c,batch); batch.clear(); lastCommit = System.nanoTime()
                        controlBoundary(c)
                        validateBinding(c,source,root.identity())
                    }
                }
            }
            if (!batch.isEmpty()) { store.batch(c,batch); batch.clear() }
            controlBoundary(c)
            validateBinding(c,source,root.identity())
        } catch (InventoryStop ignored) {
            // try-with-resources closes cursor/root BEFORE durable stop acknowledgement.
            store.checkpoint(c)
            return
        } catch (SourceAccessException e) {
            if (!batch.isEmpty()) store.batch(c,batch)
            // Preserve the discovered name even when its metadata/root/list cannot be read.
            if (store.one('SELECT id FROM scan_entry WHERE scan_id=? AND location_id=?',c.scanId,c.locationId) == null)
                store.batch(c,List.of(new InventoryEntry(path:c.path,name:new byte[0],errorCode:e.code,errorDetail:e.message)))
            if (e.code in ['WRITABLE_SOURCE','APPLICATION_STORAGE_OVERLAP']) store.block(c,e.code)
            else store.complete(c,e.code,e.message)
            return
        }
        store.complete(c)
    }
    private static InventoryEntry observe(ReadOnlyFileAccess.Root root, byte[] path, UUID parent, byte[] name, SourceDefinition source, MountTable mounts) {
        InventoryEntry entry = new InventoryEntry(parentId:parent,name:name,path:path)
        try {
            entry.metadata = root.metadata(path)
            if (entry.metadata.isRegular() && entry.metadata.size < 0L) throw new SourceAccessException('UNSUPPORTED_SIZE','The file size exceeds the supported signed 64-bit range.')
            try { entry.filesystem = mounts.byId(entry.metadata.mountId).filesystem }
            catch (SourceAccessException ignored) { /* Missing optional capability remains null. */ }
            entry.excluded = !source.crossMounts && entry.metadata.mountId != root.identity().mountId
            if (entry.metadata.isSymlink() && !entry.excluded) {
                entry.linkTarget = root.readLink(path)
                if (!entry.metadata.sameFingerprint(root.metadata(path))) throw new SourceAccessException('CHANGED','The symbolic link changed while reading its metadata.')
            }
        } catch (SourceAccessException e) { entry.errorCode = e.code; entry.errorDetail = e.message }
        entry
    }
    static byte[] append(byte[] parent, byte[] name) {
        RawPath.components(name)
        byte[] path = Arrays.copyOf(parent,parent.length+name.length+(parent.length == 0 ? 0 : 1))
        int offset = parent.length
        if (offset > 0) path[offset++] = (byte)47
        System.arraycopy(name,0,path,offset,name.length)
        path
    }
    @Override void stop() {
        if (!running) return
        stopping = true
        executor.shutdown()
        boolean stopped = false
        try { stopped = executor.awaitTermination(20L,TimeUnit.SECONDS) }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt() }
        heartbeats.shutdownNow()
        if (stopped && epoch != null) {
            try { store.release(owner,epoch) } catch (Exception ignored) { LOG.warn('Shutdown recovery will occur after the database lease expires.') }
        }
        // A blocked native syscall is never force-stopped. Its lease will expire and fence late results.
        running = false
    }
    @Override void stop(Runnable callback) { stop(); callback.run() }
}
