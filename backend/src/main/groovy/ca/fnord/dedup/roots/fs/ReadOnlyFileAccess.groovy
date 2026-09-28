package ca.fnord.dedup.roots.fs

import ca.fnord.dedup.roots.SourceDefinition
import groovy.transform.CompileStatic

/** No write, mutation, shell, upload or arbitrary download methods. Handles are thread-confined. */
@CompileStatic
interface ReadOnlyFileAccess {
    Root openRoot(SourceDefinition source)

    interface Root extends AutoCloseable {
        FileMetadata metadata(byte[] relativePath)
        DirectoryCursor list(byte[] relativePath)
        RegularFile openRegular(byte[] relativePath, FileMetadata expected)
        byte[] readLink(byte[] relativePath)
        FileMetadata identity()
        void close()
    }
    interface DirectoryCursor extends AutoCloseable {
        /** Next exact basename; null at end. One entry at a time. */
        byte[] next()
        void close()
    }
    interface RegularFile extends AutoCloseable {
        /** At most buffer.length bytes; -1 at EOF. Maximum buffer is 4 MiB. */
        int read(byte[] buffer)
        FileMetadata metadata()
        /** True only after EOF, expected byte count and unchanged descriptor/path evidence. */
        boolean validateComplete()
        void close()
    }
}
