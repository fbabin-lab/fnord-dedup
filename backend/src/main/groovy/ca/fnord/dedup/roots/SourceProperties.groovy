package ca.fnord.dedup.roots

import groovy.transform.CompileStatic
import org.springframework.boot.context.properties.ConfigurationProperties

@CompileStatic
@ConfigurationProperties('fnord')
class SourceProperties {
    List<SourceDefinition> sources = new ArrayList<>()
    String artifactDirectory = '/var/lib/fnord/artifacts'
}
