package ca.fnord.dedup.roots.fs

import groovy.transform.CompileStatic

@CompileStatic
class SourceAccessException extends IOException {
    final String code
    final int errno
    SourceAccessException(String code, String message, int errno = 0) {
        super(message)
        this.code = code
        this.errno = errno
    }
}
