package ca.fnord.dedup.roots.fs

import ca.fnord.dedup.roots.SourceDefinition
import groovy.transform.CompileStatic

@CompileStatic
class MountGuard {
    void validateRoot(NativeLinux linux, int fd, SourceDefinition source) {
        requireReadOnly(linux, fd)
        MountTable.current().requireReadOnly(linux.metadata(fd).mountId, source.containerPath, source.crossMounts)
    }
    void requireReadOnly(NativeLinux linux, int fd) {
        if (!linux.readOnly(fd)) throw new SourceAccessException('WRITABLE_SOURCE', 'The opened source mount is writable.')
        if (!MountTable.current().byId(linux.metadata(fd).mountId).readOnly)
            throw new SourceAccessException('WRITABLE_SOURCE', 'The opened mount is not read-only in the mount table.')
    }
}
