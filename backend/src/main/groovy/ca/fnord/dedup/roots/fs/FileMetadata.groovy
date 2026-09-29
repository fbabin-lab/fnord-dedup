package ca.fnord.dedup.roots.fs

import groovy.transform.CompileStatic
import groovy.transform.Immutable

@CompileStatic
@Immutable
class FileMetadata {
    int mask
    int mode
    long inode
    long size
    long mountId
    long deviceMajor
    long deviceMinor
    long mtimeSeconds
    int mtimeNanos
    long ctimeSeconds
    int ctimeNanos
    Long birthSeconds
    Integer birthNanos
    Long linkCount
    Long blocks
    Long uid
    Long gid

    boolean isRegular() { (mode & 0170000) == 0100000 }
    boolean isDirectory() { (mode & 0170000) == 0040000 }
    boolean isSymlink() { (mode & 0170000) == 0120000 }
    boolean sameObject(FileMetadata other) {
        other != null && inode == other.inode && deviceMajor == other.deviceMajor &&
            deviceMinor == other.deviceMinor && mountId == other.mountId
    }
    boolean sameFingerprint(FileMetadata other) {
        sameObject(other) && mode == other.mode && size == other.size &&
            mtimeSeconds == other.mtimeSeconds && mtimeNanos == other.mtimeNanos &&
            ctimeSeconds == other.ctimeSeconds && ctimeNanos == other.ctimeNanos
    }
}
