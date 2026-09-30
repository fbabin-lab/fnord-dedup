package ca.fnord.dedup.roots.fs

import ca.fnord.dedup.roots.SourceDefinition
import com.sun.jna.Pointer
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.concurrent.Semaphore

@CompileStatic
@Component
final class LinuxReadOnlyFileAccess implements ReadOnlyFileAccess {
    private final MountGuard mountGuard
    private final Semaphore handles = new Semaphore(64)

    LinuxReadOnlyFileAccess() { this.mountGuard = new MountGuard() }
    // Test seam for disposable writable native fixtures, never exposed through configuration.
    @PackageScope LinuxReadOnlyFileAccess(MountGuard guard) { this.mountGuard = guard }

    @Override
    ReadOnlyFileAccess.Root openRoot(SourceDefinition source) {
        if (!source.enabled) throw new SourceAccessException('SOURCE_DISABLED', 'This source is disabled.')
        String path = source.containerPath
        if (path == null || !path.startsWith('/') || path.indexOf(0) >= 0)
            throw new IllegalArgumentException('The configured root must be an absolute path.')
        if (!source.unsafeFast) RawPath.components(path.substring(1).getBytes(StandardCharsets.UTF_8))
        NativeLinux linux = new NativeLinux()
        int fd = open(linux, -100, path.getBytes(StandardCharsets.UTF_8), NativeLinux.DIRECTORY, 0L)
        try {
            if (!source.unsafeFast) mountGuard.validateRoot(linux, fd, source)
            return new RootHandle(linux, fd, linux.metadata(fd), source.crossMounts, source.unsafeFast)
        } catch (Throwable t) { closeFd(linux, fd); throw t }
    }

    private int open(NativeLinux linux, int parent, byte[] name, int flags, long resolve) {
        if (!handles.tryAcquire()) throw new SourceAccessException('OPEN_HANDLE_LIMIT', 'The source handle limit has been reached.')
        try { return linux.open(parent, name, flags, resolve) }
        catch (Throwable t) { handles.release(); throw t }
    }
    private void closeFd(NativeLinux linux, int fd) {
        try { linux.close(fd) } finally { handles.release() }
    }

    private final class RootHandle implements ReadOnlyFileAccess.Root {
        private final NativeLinux linux
        private final int fd
        private final FileMetadata original
        private final boolean crossMounts
        private final boolean unsafeFast
        private final Set<AutoCloseable> children = new LinkedHashSet<>()
        private boolean closed

        RootHandle(NativeLinux linux, int fd, FileMetadata original, boolean crossMounts, boolean unsafeFast) {
            this.linux = linux; this.fd = fd; this.original = original; this.crossMounts = crossMounts; this.unsafeFast = unsafeFast
        }
        @Override FileMetadata identity() { ensureOpen(); original }

        private void ensureOpen() {
            if (closed) throw new IllegalStateException('The source handle is closed.')
        }
        private int resolve(byte[] path, int flags, boolean boundaryMetadata = false) {
            ensureOpen()
            if (unsafeFast) {
                byte[] target = path.length == 0 ? '.'.getBytes(StandardCharsets.US_ASCII) : path
                return open(linux, fd, target, flags, NativeLinux.BENEATH)
            }
            List<byte[]> parts = RawPath.components(path)
            int parent = fd
            boolean ownsParent = false
            try {
                if (parts.isEmpty()) return open(linux, fd, '.'.getBytes(StandardCharsets.US_ASCII), flags, NativeLinux.BENEATH)
                for (int i = 0; i < parts.size(); i++) {
                    boolean last = i == parts.size() - 1
                    long restrictions = NativeLinux.BENEATH
                    if (!crossMounts && !(last && boundaryMetadata)) restrictions |= NativeLinux.NO_XDEV
                    int next = open(linux, parent, parts.get(i), last ? flags : NativeLinux.DIRECTORY, restrictions)
                    try {
                        // Final boundary metadata is allowed; no traversal or body read follows it.
                        if (!(last && boundaryMetadata)) mountGuard.requireReadOnly(linux, next)
                    } catch (Throwable t) { closeFd(linux, next); throw t }
                    if (ownsParent) closeFd(linux, parent)
                    parent = next; ownsParent = true
                }
                ownsParent = false
                return parent
            } finally { if (ownsParent) closeFd(linux, parent) }
        }

        @Override FileMetadata metadata(byte[] path) {
            int entry = resolve(path, NativeLinux.PATH, true)
            try { return linux.metadata(entry) } finally { closeFd(linux, entry) }
        }
        @Override byte[] readLink(byte[] path) {
            int entry = resolve(path, NativeLinux.PATH, true)
            try {
                if (!unsafeFast && !linux.metadata(entry).isSymlink()) throw new SourceAccessException('NOT_A_SYMLINK', 'The entry is not a symbolic link.')
                return linux.readLink(entry)
            } finally { closeFd(linux, entry) }
        }
        @Override ReadOnlyFileAccess.DirectoryCursor list(byte[] path) {
            int entry = resolve(path, NativeLinux.DIRECTORY)
            Pointer directory
            FileMetadata originalDirectory = null
            try { if (!unsafeFast) originalDirectory = linux.metadata(entry); directory = linux.directory(entry) }
            catch (Throwable t) { closeFd(linux, entry); throw t }
            Cursor cursor = new Cursor(entry, directory, path.clone() as byte[], originalDirectory)
            children.add(cursor)
            return cursor
        }
        @Override ReadOnlyFileAccess.RegularFile openRegular(byte[] path, FileMetadata expected) {
            if (expected == null || !expected.isRegular()) throw new SourceAccessException('NOT_REGULAR', 'Only observed regular files can be opened for content.')
            int entry = resolve(path, NativeLinux.NONBLOCK)
            try {
                FileMetadata before = linux.metadata(entry)
                if (!before.isRegular()) throw new SourceAccessException('NOT_REGULAR', 'Only regular files can be read.')
                if (!unsafeFast && (!expected.sameFingerprint(before) || !before.sameFingerprint(metadata(path))))
                    throw new SourceAccessException('CHANGED', 'The observed file has changed.')
                FileHandle result = new FileHandle(entry, path.clone() as byte[], before)
                children.add(result)
                return result
            } catch (Throwable t) { closeFd(linux, entry); throw t }
        }
        @Override void close() {
            if (closed) return
            closed = true
            Throwable problem = null
            for (AutoCloseable child : new ArrayList<AutoCloseable>(children)) {
                try { child.close() } catch (Throwable t) { if (problem == null) problem = t; else problem.addSuppressed(t) }
            }
            try { closeFd(linux, fd) } catch (Throwable t) { if (problem == null) problem = t; else problem.addSuppressed(t) }
            if (problem != null) throw problem
        }

        private final class Cursor implements ReadOnlyFileAccess.DirectoryCursor {
            private final int entry
            private final Pointer directory
            private final byte[] path
            private final FileMetadata originalDirectory
            private boolean done
            Cursor(int entry, Pointer directory, byte[] path, FileMetadata originalDirectory) {
                this.entry = entry; this.directory = directory; this.path = path; this.originalDirectory = originalDirectory
            }
            @Override byte[] next() {
                ensureOpen()
                if (done) throw new IllegalStateException('The directory cursor is closed.')
                if (!unsafeFast) {
                    mountGuard.requireReadOnly(linux, entry)
                    if (!originalDirectory.sameObject(RootHandle.this.metadata(path)))
                        throw new SourceAccessException('CHANGED', 'The directory entry changed during enumeration.')
                }
                return linux.next(directory)
            }
            @Override void close() {
                if (!done) {
                    done = true
                    try { linux.closeDirectory(directory) }
                    finally { handles.release(); children.remove(this) }
                }
            }
        }
        private final class FileHandle implements ReadOnlyFileAccess.RegularFile {
            private final int entry
            private final byte[] path
            private final FileMetadata before
            private long bytesRead
            private boolean eof, done
            FileHandle(int entry, byte[] path, FileMetadata before) {
                this.entry = entry; this.path = path; this.before = before
            }
            private void check() {
                ensureOpen()
                if (done) throw new IllegalStateException('The file handle is closed.')
            }
            @Override int read(byte[] buffer) {
                check()
                if (!unsafeFast) mountGuard.requireReadOnly(linux, entry)
                int count = linux.read(entry, buffer)
                if (count < 0) eof = true
                else {
                    bytesRead = Math.addExact(bytesRead, (long)count)
                    if (!unsafeFast && bytesRead > before.size) throw new SourceAccessException('CHANGED', 'The file grew during reading.', 0, count)
                }
                return count
            }
            @Override FileMetadata metadata() { check(); linux.metadata(entry) }
            @Override boolean validateComplete() {
                check()
                if (unsafeFast) return eof
                mountGuard.requireReadOnly(linux, entry)
                try {
                    return eof && bytesRead == before.size && before.sameFingerprint(metadata()) &&
                        before.sameFingerprint(RootHandle.this.metadata(path))
                } catch (SourceAccessException ignored) { return false }
            }
            @Override void close() {
                if (!done) {
                    done = true
                    try { closeFd(linux, entry) } finally { children.remove(this) }
                }
            }
        }
    }
}
