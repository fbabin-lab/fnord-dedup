package ca.fnord.dedup.roots

import groovy.transform.CompileStatic

@CompileStatic
class SourceDefinition {
    UUID id
    UUID sourceInstanceId
    String key
    String label
    String containerPath
    String hostExportPrefix
    boolean enabled = true
    boolean crossMounts = false
}
