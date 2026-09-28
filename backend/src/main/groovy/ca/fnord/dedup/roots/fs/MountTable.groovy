package ca.fnord.dedup.roots.fs

import groovy.transform.CompileStatic
import groovy.transform.Immutable
import java.nio.file.Files
import java.nio.file.Path

@CompileStatic
final class MountTable {
    @Immutable
    static class Mount {
        long id
        long parentId
        String device
        String root
        String path
        boolean readOnly
        String filesystem
    }
    final List<Mount> mounts
    MountTable(List<Mount> mounts) { this.mounts = List.copyOf(mounts) }

    static MountTable current() {
        parse(Files.readAllLines(Path.of('/proc/self/mountinfo')))
    }
    static MountTable parse(List<String> lines) {
        List<Mount> result = new ArrayList<>()
        for (String line : lines) {
            String[] sides = line.split(' - ', 2)
            if (sides.length != 2) throw new SourceAccessException('MOUNT_STATUS_UNAVAILABLE', 'Invalid mount table.')
            String[] left = sides[0].split(' '), right = sides[1].split(' ')
            if (left.length < 6 || right.length < 3) throw new SourceAccessException('MOUNT_STATUS_UNAVAILABLE', 'Invalid mount table.')
            result.add(new Mount(Long.parseLong(left[0]), Long.parseLong(left[1]), left[2], unescape(left[3]), unescape(left[4]),
                Arrays.asList(left[5].split(',')).contains('ro'), right[0]))
        }
        new MountTable(result)
    }
    Mount byId(long id) {
        for (Mount m : mounts) if (m.id == id) return m
        throw new SourceAccessException('MOUNT_STATUS_UNAVAILABLE', 'Opened mount is not in the current mount table.')
    }
    List<Mount> beneath(String root) {
        List<Mount> result = new ArrayList<>()
        for (Mount m : mounts) if (isWithin(m.path, root) && m.path != root) result.add(m)
        result
    }
    String backingPath(long mountId, String containerPath) {
        Mount mount = byId(mountId)
        if (!isWithin(containerPath, mount.path))
            throw new SourceAccessException('MOUNT_STATUS_UNAVAILABLE', 'The configured path no longer belongs to the opened mount.')
        String suffix = containerPath == mount.path ? '' : (mount.path == '/' ? containerPath : containerPath.substring(mount.path.length()))
        return mount.root == '/' ? (suffix.isEmpty() ? '/' : suffix) : mount.root + suffix
    }
    void requireReadOnly(long id, String root, boolean crossMounts) {
        if (!byId(id).readOnly) throw new SourceAccessException('WRITABLE_SOURCE', 'The effective source mount is writable.')
        if (crossMounts) for (Mount m : beneath(root)) {
            if (!m.readOnly) throw new SourceAccessException('WRITABLE_SUBMOUNT', 'An included nested mount is writable.')
        }
    }
    static boolean isWithin(String child, String parent) {
        child == parent || child.startsWith(parent == '/' ? '/' : parent + '/')
    }
    private static String unescape(String value) {
        // mountinfo escapes exactly these four byte values. Decode backslash last.
        value.replace('\\040', ' ').replace('\\011', '\t').replace('\\012', '\n').replace('\\134', '\\')
    }
}
