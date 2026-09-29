package ca.fnord.dedup.roots

import groovy.transform.CompileStatic
import groovy.transform.Immutable

@CompileStatic
@Immutable
class SourceView {
    UUID id
    UUID sourceInstanceId
    String key
    String label
    String containerPath
    boolean enabled
    boolean crossMounts
    String status
    String detail
    int excludedMountCount
}
