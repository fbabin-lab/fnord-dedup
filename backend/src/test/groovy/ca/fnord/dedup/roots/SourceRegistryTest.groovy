package ca.fnord.dedup.roots

import ca.fnord.dedup.roots.fs.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import static org.junit.jupiter.api.Assertions.*

class SourceRegistryTest {
    @Test void sourceValidationFailureDoesNotPoisonOtherSources() {
        def nativeLinux = new NativeLinux()
        int fd = nativeLinux.open(-100, '/'.bytes, NativeLinux.DIRECTORY, 0L)
        FileMetadata known
        try { known = nativeLinux.metadata(fd) } finally { nativeLinux.close(fd) }
        def missing = new FileMetadata(known.mask,known.mode,known.inode+1,known.size,Long.MAX_VALUE,
            known.deviceMajor,known.deviceMinor,known.mtimeSeconds,known.mtimeNanos,known.ctimeSeconds,
            known.ctimeNanos,known.birthSeconds,known.birthNanos,known.linkCount,known.blocks,known.uid,known.gid)
        def good = new SourceDefinition(id:UUID.randomUUID(), sourceInstanceId:UUID.randomUUID(),key:'good',label:'Good',containerPath:'/sources/good')
        def bad = new SourceDefinition(id:UUID.randomUUID(), sourceInstanceId:UUID.randomUUID(),key:'bad',label:'Bad',containerPath:'/sources/bad')
        // A disappearing mount after open is a source-local error. This test supplies
        // only descriptor metadata; real filesystem confinement is covered separately.
        ReadOnlyFileAccess access = [openRoot:{ SourceDefinition s ->
            [identity:{ s.id == good.id ? known : missing },close:{}] as ReadOnlyFileAccess.Root
        }] as ReadOnlyFileAccess
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override int update(String sql, Object... arguments) { 1 }
        }
        def registry = new SourceRegistry(new SourceProperties(sources:[good,bad]),access,jdbc,JsonMapper.builder().build())
        registry.initialize()
        assertEquals('AVAILABLE',registry.list().find { it.id == good.id }.status)
        assertEquals('MOUNT_STATUS_UNAVAILABLE',registry.list().find { it.id == bad.id }.status)
        assertEquals(64,registry.revision.length())
    }
}
