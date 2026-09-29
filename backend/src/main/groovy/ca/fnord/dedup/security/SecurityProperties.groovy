package ca.fnord.dedup.security

import groovy.transform.CompileStatic
import org.springframework.boot.context.properties.ConfigurationProperties

@CompileStatic
@ConfigurationProperties('fnord.security')
class SecurityProperties {
    String username = 'operator'
    String passwordHash
    boolean secureCookies = false
}
