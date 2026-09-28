package ca.fnord.dedup.roots.fs

import ca.fnord.dedup.roots.SourceDefinition
import com.sun.jna.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import static org.junit.jupiter.api.Assertions.*

class LinuxReadOnlyFileAccessTest {
    @TempDir Path fixture
    LinuxReadOnlyFileAccess access
    SourceDefinition source

    // These tests exercise real syscalls on generated writable fixtures. They do NOT
    // prove mount enforcement; production guard and Docker mount tests do that separately.
    static class FixtureMountGuard extends MountGuard {
        @Override void validateRoot(NativeLinux n, int fd, SourceDefinition s) {}
        @Override void requireReadOnly(NativeLinux n, int fd) {}
    }
    interface FixtureLibC extends Library {
        int open(byte[] name, int flags, int mode)
        long write(int fd, byte[] bytes, long count)
        int close(int fd)
        int mkfifo(String path, int mode)
        int mkdirat(int parent, byte[] name, int mode)
        int openat(int parent, byte[] name, int flags, int mode)
        int unlinkat(int parent, byte[] name, int flags)
    }
    @BeforeEach void setup() {
        access = new LinuxReadOnlyFileAccess(new FixtureMountGuard())
        source = new SourceDefinition(id:UUID.randomUUID(),sourceInstanceId:UUID.randomUUID(),
            key:'fixture',label:'Generated fixture',containerPath:fixture.toString())
    }
    static byte[] bytes(String value) { value.getBytes('UTF-8') }

    @Test void writableMountIsRejectedWithoutMutation() {
        Files.writeString(fixture.resolve('file'), 'hello')
        def before = Files.readAttributes(fixture.resolve('file'), 'unix:mode,ino,size,lastModifiedTime,ctime')
        def error = assertThrows(SourceAccessException) { new LinuxReadOnlyFileAccess().openRoot(source) }
        assertEquals('WRITABLE_SOURCE', error.code)
        assertEquals(before, Files.readAttributes(fixture.resolve('file'), 'unix:mode,ino,size,lastModifiedTime,ctime'))
    }
    @Test void namesAreLosslessAndControlCharactersAreEscaped() {
        def names = ['space name', '"quote,comma"', 'line\nfeed', 'tab\tname', 'carriage\rreturn',
            '-leading', 'back\\slash', 'caf\u00e9', 'cafe\u0301', '.hidden', '<img src=x onerror=alert(1)>']
        names.each { Files.write(fixture.resolve(it), bytes('hello')) }
        byte[] invalid = [(byte)0xff,(byte)0xfe,(byte)65] as byte[]
        def nativeFixture = Native.load('c',FixtureLibC)
        byte[] prefix = bytes(fixture.toString() + '/')
        byte[] full = new byte[prefix.length + invalid.length + 1]
        System.arraycopy(prefix,0,full,0,prefix.length)
        System.arraycopy(invalid,0,full,prefix.length,invalid.length)
        int fd = nativeFixture.open(full, 0x41 | 0x80, 0600)
        assertTrue(fd >= 0)
        try { assertEquals(5L,nativeFixture.write(fd,bytes('hello'),5L)) } finally { nativeFixture.close(fd) }
        Set<String> actual = [] as Set
        try (def root = access.openRoot(source); def cursor = root.list(new byte[0])) {
            byte[] name
            while ((name = cursor.next()) != null) {
                actual.add(Base64.encoder.encodeToString(name))
                def meta = root.metadata(name)
                assertTrue(meta.regular)
                try (def file = root.openRegular(name,meta)) {
                    byte[] data = new byte[32]
                    assertEquals(5,file.read(data)); assertEquals(-1,file.read(data))
                    assertTrue(file.validateComplete())
                }
            }
        }
        assertEquals((names.collect { Base64.encoder.encodeToString(bytes(it)) } + Base64.encoder.encodeToString(invalid)) as Set, actual)
        assertEquals('line\\u000afeed', RawPath.display(bytes('line\nfeed')))
        assertEquals('[bytes] \\xff\\xfeA',RawPath.display(invalid))
        assertNotEquals(RawPath.display(bytes('caf\u00e9')), RawPath.display(bytes('cafe\u0301')))
    }
    @Test void refusesSymlinksTraversalAndSpecialFiles() {
        Files.writeString(fixture.resolve('file'),'hello')
        Files.createSymbolicLink(fixture.resolve('inside'),Path.of('file'))
        Files.createSymbolicLink(fixture.resolve('outside'),Path.of('/etc/passwd'))
        Files.createSymbolicLink(fixture.resolve('loop'),Path.of('.'))
        assertEquals(0,Native.load('c',FixtureLibC).mkfifo(fixture.resolve('fifo').toString(),0600))
        try (def root = access.openRoot(source)) {
            def ordinary = root.metadata(bytes('file'))
            assertTrue(root.metadata(bytes('inside')).symlink)
            assertArrayEquals(bytes('/etc/passwd'),root.readLink(bytes('outside')))
            for (String name : ['inside','outside','fifo'])
                assertThrows(SourceAccessException) { root.openRegular(bytes(name),ordinary) }
            assertThrows(SourceAccessException) { root.list(bytes('loop')) }
            for (String path : ['/etc/passwd','../file','a/../file','a//file','./file','file/'])
                assertThrows(IllegalArgumentException) { root.metadata(bytes(path)) }
        }
    }
    @Test void rejectsReplacedPathAndDiscardedIncompleteReads() {
        Path path = fixture.resolve('file')
        Files.writeString(path,'hello')
        try (def root = access.openRoot(source)) {
            def original = root.metadata(bytes('file'))
            try (def file = root.openRegular(bytes('file'),original)) {
                byte[] data = new byte[2]
                assertEquals(2,file.read(data)); assertFalse(file.validateComplete())
            }
            try (def file = root.openRegular(bytes('file'),original)) {
                Files.move(path,fixture.resolve('old'))
                Files.writeString(path,'hello')
                byte[] data = new byte[16]
                assertEquals(5,file.read(data)); assertEquals(-1,file.read(data))
                assertFalse(file.validateComplete())
            }
            assertThrows(SourceAccessException) { root.openRegular(bytes('file'),original) }
        }
    }
    @Test void directoryReplacementCannotRedirectReads() {
        Files.createDirectory(fixture.resolve('directory'))
        Files.writeString(fixture.resolve('directory/file'),'hello')
        try (def root = access.openRoot(source)) {
            def before = root.metadata(bytes('directory/file'))
            Files.move(fixture.resolve('directory'),fixture.resolve('moved'))
            Files.createSymbolicLink(fixture.resolve('directory'),fixture.resolve('moved'))
            assertThrows(SourceAccessException) { root.openRegular(bytes('directory/file'),before) }
        }
    }
    @Test void boundedReadsAndClosingRootReleasesChildren() {
        Path path = fixture.resolve('large')
        try (def f = new RandomAccessFile(path.toFile(),'rw')) { f.setLength(512L * 1024 * 1024) }
        def root = access.openRoot(source)
        def expected = root.metadata(bytes('large'))
        def file = root.openRegular(bytes('large'),expected)
        byte[] buffer = new byte[65536]
        long count = 0
        int read
        while ((read = file.read(buffer)) >= 0) count += read
        assertEquals(expected.size,count); assertTrue(file.validateComplete())
        root.close(); root.close(); file.close()
        assertThrows(IllegalStateException) { file.read(buffer) }
        // Exceeds the adapter handle limit over time; no live-handle leak is allowed.
        100.times {
            try (def r = access.openRoot(source)) {
                r.list(new byte[0])
                r.openRegular(bytes('large'),r.metadata(bytes('large')))
            }
        }
    }
    @Test void exactNanosecondsArePartOfFingerprint() {
        Files.writeString(fixture.resolve('file'),'x')
        try (def root = access.openRoot(source)) {
            def a = root.metadata(bytes('file'))
            def b = new FileMetadata(a.mask,a.mode,a.inode,a.size,a.mountId,a.deviceMajor,a.deviceMinor,
                a.mtimeSeconds,(a.mtimeNanos + 1) % 1000000000,a.ctimeSeconds,a.ctimeNanos,
                a.birthSeconds,a.birthNanos,a.linkCount,a.blocks,a.uid,a.gid)
            assertFalse(a.sameFingerprint(b))
        }
    }
    @Test void deepPathsUseComponentDescriptorsAndEmptyFilesValidate() {
        def libc = Native.load('c',FixtureLibC)
        byte[] rootBytes = Arrays.copyOf(bytes(fixture.toString()),bytes(fixture.toString()).length + 1)
        int parent = libc.open(rootBytes,NativeLinux.DIRECTORY,0)
        String component = 'd' * 100
        byte[] name = Arrays.copyOf(bytes(component),101)
        try {
            45.times {
                assertEquals(0,libc.mkdirat(parent,name,0700))
                int child = libc.openat(parent,name,NativeLinux.DIRECTORY,0)
                assertTrue(child >= 0); libc.close(parent); parent = child
            }
            int file = libc.openat(parent,[(byte)120,(byte)0] as byte[],0x41 | 0x80,0600)
            assertTrue(file >= 0); libc.close(file)
        } finally { libc.close(parent) }
        byte[] path = bytes(([component]*45).join('/')+'/x')
        assertTrue(path.length > 4096)
        try {
            try (def root = access.openRoot(source); def file = root.openRegular(path,root.metadata(path))) {
                assertEquals(-1,file.read(new byte[8])); assertTrue(file.validateComplete())
            }
        } finally {
            // JUnit's pathname-based recursive cleanup cannot remove paths beyond PATH_MAX.
            // Only this generated fixture is removed, descriptor-relative, in test code.
            List<Integer> parents = [libc.open(rootBytes,NativeLinux.DIRECTORY,0)]
            try {
                45.times { parents.add(libc.openat(parents.last(),name,NativeLinux.DIRECTORY,0)) }
                assertEquals(0,libc.unlinkat(parents.last(),[(byte)120,(byte)0] as byte[],0))
                for (int i=parents.size()-1; i>0; i--) {
                    libc.close(parents.remove(i))
                    assertEquals(0,libc.unlinkat(parents.get(i-1),name,0x200))
                }
            } finally { parents.each { libc.close(it) } }
        }
    }
    @Test void directoryCursorDetectsReplacementAndFailureDoesNotLeakHandles() {
        Files.createDirectory(fixture.resolve('directory'))
        Files.writeString(fixture.resolve('directory/file'),'hello')
        try (def root = access.openRoot(source); def cursor = root.list(bytes('directory'))) {
            Files.move(fixture.resolve('directory'),fixture.resolve('old-directory'))
            Files.createDirectory(fixture.resolve('directory'))
            assertEquals('CHANGED',assertThrows(SourceAccessException) { cursor.next() }.code)
            100.times { assertThrows(SourceAccessException) { root.metadata(bytes('missing')) } }
            assertTrue(root.metadata(bytes('old-directory/file')).regular)
        }
    }
}
