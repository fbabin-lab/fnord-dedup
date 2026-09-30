package ca.fnord.dedup.roots.fs

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import groovy.transform.CompileStatic

/** Linux/amd64 ABI only. All native flags and layouts are kept in this adapter. */
@CompileStatic
final class NativeLinux {
    static final int DIRECTORY = 0x10000, NOFOLLOW = 0x20000, CLOEXEC = 0x80000
    static final int PATH = 0x200000, NONBLOCK = 0x800
    static final long NO_XDEV = 1L, NO_MAGICLINKS = 2L, NO_SYMLINKS = 4L, BENEATH = 8L
    static final int MAX_BUFFER = 4 * 1024 * 1024
    private final LibC libc

    interface LibC extends Library {
        long syscall(long number, Object... args)
        int statx(int dirfd, Pointer path, int flags, int mask, Pointer buffer)
        int fstatvfs(int fd, Pointer buffer)
        long read(int fd, byte[] buffer, long count)
        long readlinkat(int fd, Pointer path, byte[] buffer, long count)
        Pointer fdopendir(int fd)
        Pointer readdir(Pointer directory)
        int closedir(Pointer directory)
        int close(int fd)
    }

    NativeLinux() {
        if (System.getProperty('os.name') != 'Linux' || System.getProperty('os.arch') != 'amd64' || Native.POINTER_SIZE != 8)
            throw new SourceAccessException('UNSUPPORTED_PLATFORM', 'Source access requires Linux/amd64.')
        libc = Native.load('c', LibC)
    }

    int open(int parent, byte[] name, int flags, long resolve) {
        Memory path = cString(name)
        Memory how = new Memory(24)
        how.clear()
        how.setLong(0, (long)(flags | CLOEXEC | NOFOLLOW))
        how.setLong(16, resolve | NO_MAGICLINKS | NO_SYMLINKS)
        try {
            long fd = libc.syscall(437L, (long)parent, path, how, 24L)
            if (fd < 0) fail('openat2', 'OPEN_REJECTED')
            return (int)fd
        } finally { path.close(); how.close() }
    }

    FileMetadata metadata(int fd) {
        Memory empty = cString(new byte[0])
        Memory data = new Memory(256)
        data.clear()
        try {
            if (libc.statx(fd, empty, 0x1000 | 0x100, 0x1fff, data) < 0) fail('statx', 'METADATA_UNAVAILABLE')
            int mask = data.getInt(0)
            if ((mask & 0x13c3) != 0x13c3)
                throw new SourceAccessException('UNSUPPORTED_METADATA', 'Required precise identity and timestamp metadata is unavailable.')
            return new FileMetadata(mask, Short.toUnsignedInt(data.getShort(28)), data.getLong(32),
                data.getLong(40), data.getLong(144), unsigned(data,136), unsigned(data,140),
                data.getLong(112), data.getInt(120), data.getLong(96), data.getInt(104),
                (mask & 0x800) != 0 ? data.getLong(80) : null, (mask & 0x800) != 0 ? data.getInt(88) : null,
                (mask & 4) != 0 ? unsigned(data,16) : null, (mask & 0x400) != 0 ? data.getLong(48) : null,
                (mask & 8) != 0 ? unsigned(data,20) : null, (mask & 16) != 0 ? unsigned(data,24) : null)
        } finally { empty.close(); data.close() }
    }

    boolean readOnly(int fd) {
        Memory data = new Memory(112)
        data.clear()
        try {
            if (libc.fstatvfs(fd, data) < 0) fail('fstatvfs', 'MOUNT_STATUS_UNAVAILABLE')
            return (data.getLong(72) & 1L) != 0L
        } finally { data.close() }
    }

    int read(int fd, byte[] buffer) {
        if (buffer.length == 0 || buffer.length > MAX_BUFFER) throw new IllegalArgumentException('Read buffer must be 1 byte to 4 MiB.')
        long count = libc.read(fd, buffer, (long)buffer.length)
        if (count < 0) fail('read', 'READ_FAILED')
        return count == 0 ? -1 : (int)count
    }

    byte[] readLink(int fd) {
        Memory empty = cString(new byte[0])
        try {
            byte[] buffer = new byte[65536]
            long count = libc.readlinkat(fd, empty, buffer, (long)buffer.length)
            if (count < 0) fail('readlinkat', 'LINK_METADATA_FAILED')
            if (count == buffer.length) throw new SourceAccessException('LINK_TARGET_TOO_LONG', 'Link target exceeds the metadata limit.')
            return Arrays.copyOf(buffer, (int)count)
        } finally { empty.close() }
    }

    Pointer directory(int fd) {
        Pointer directory = libc.fdopendir(fd)
        if (directory == null) fail('fdopendir', 'DIRECTORY_OPEN_FAILED')
        return directory
    }
    byte[] next(Pointer directory) {
        while (true) {
            Native.setLastError(0)
            Pointer row = libc.readdir(directory)
            if (row == null) {
                if (Native.getLastError() != 0) fail('readdir', 'DIRECTORY_READ_FAILED')
                return null
            }
            int length = Short.toUnsignedInt(row.getShort(16))
            if (length < 20 || length > 280) throw new SourceAccessException('UNSUPPORTED_ABI', 'Unexpected directory entry layout.')
            int end = 19
            while (end < length && row.getByte(end) != 0) end++
            if (end == length) throw new SourceAccessException('UNSUPPORTED_ABI', 'Directory entry has no terminator.')
            byte[] name = row.getByteArray(19, end - 19)
            if ((name.length == 1 && name[0] == 46) || (name.length == 2 && name[0] == 46 && name[1] == 46)) continue
            RawPath.components(name)
            return name
        }
    }
    void closeDirectory(Pointer directory) { if (libc.closedir(directory) != 0) fail('closedir', 'CLOSE_FAILED') }
    void close(int fd) { if (libc.close(fd) != 0) fail('close', 'CLOSE_FAILED') }

    private static long unsigned(Pointer data, long offset) { Integer.toUnsignedLong(data.getInt(offset)) }
    private static Memory cString(byte[] value) {
        for (byte b : value) if (b == 0) throw new IllegalArgumentException('NUL is not a path byte.')
        Memory memory = new Memory(value.length + 1L)
        memory.write(0, value, 0, value.length)
        memory.setByte(value.length, (byte)0)
        return memory
    }
    private static void fail(String operation, String code) {
        throw failure(operation, code, Native.getLastError())
    }

    @groovy.transform.PackageScope
    static SourceAccessException failure(String operation, String code, int errno) {
        String mapped = code
        // ENOSYS means a required native operation is unavailable on this runtime.
        // EINVAL is operation-specific (bad flags/ABI/arguments) and must not be
        // mislabeled as an unsupported operating system.
        if (errno == 38) mapped = 'UNSUPPORTED_PLATFORM'
        else if (errno == 2) mapped = 'UNAVAILABLE'
        else if (errno == 13) mapped = 'UNREADABLE'
        else if (errno == 18) mapped = 'MOUNT_BOUNDARY'
        else if (errno == 40) mapped = 'SYMLINK_REJECTED'
        return new SourceAccessException(mapped,
            'Native source operation ' + operation + ' rejected (' + mapped + ', errno ' + errno + ').', errno)
    }
}
