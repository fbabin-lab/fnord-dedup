package ca.fnord.dedup.inventory

import ca.fnord.dedup.roots.fs.FileMetadata
import groovy.transform.CompileStatic
import java.time.Instant

@CompileStatic
class JobProblem extends RuntimeException {
    final int status
    final String code
    JobProblem(int status, String code, String detail) { super(detail); this.status = status; this.code = code }
}

@CompileStatic
class LeaseLost extends RuntimeException {
    LeaseLost() { super('The database lease no longer belongs to this worker.') }
}

@CompileStatic
class InventoryStop extends RuntimeException {}

@CompileStatic
class InventoryEntry {
    UUID parentId
    byte[] name
    byte[] path
    FileMetadata metadata
    byte[] linkTarget
    String filesystem
    String errorCode
    String errorDetail
    boolean excluded
    Instant observedAt = Instant.now()

    String type() {
        metadata == null ? 'UNKNOWN' : metadata.isDirectory() ? 'DIRECTORY' :
            metadata.isRegular() ? 'REGULAR' : metadata.isSymlink() ? 'SYMLINK' : 'SPECIAL'
    }

    static Map<String,Object> identity(FileMetadata m) {
        [inode:Long.toUnsignedString(m.inode), mountId:Long.toUnsignedString(m.mountId),
         deviceMajor:m.deviceMajor, deviceMinor:m.deviceMinor] as Map<String,Object>
    }

    Map<String,Object> fingerprint() {
        Map<String,Object> value = new LinkedHashMap<>()
        value.put('type', type())
        if (metadata != null) {
            value.putAll(identity(metadata))
            value.putAll([mode:metadata.mode, size:Long.toString(metadata.size), mtimeSeconds:metadata.mtimeSeconds,
                mtimeNanos:metadata.mtimeNanos, ctimeSeconds:metadata.ctimeSeconds, ctimeNanos:metadata.ctimeNanos] as Map<String,Object>)
        }
        value.put('linkTarget', linkTarget == null ? null : Base64.encoder.encodeToString(linkTarget))
        value.put('errorCode', errorCode)
        value
    }
}

@CompileStatic
class WorkClaim {
    UUID id
    UUID jobId
    UUID scanId
    UUID locationId
    UUID sourceId
    UUID sourceInstanceId
    UUID owner
    long schedulerToken
    long token
    byte[] path
    String configurationRevision
    String sourceSnapshot
    String rootIdentity
}
