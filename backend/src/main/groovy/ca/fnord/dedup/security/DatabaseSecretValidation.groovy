package ca.fnord.dedup.security

import groovy.transform.CompileStatic
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@CompileStatic
@Component
class DatabaseSecretValidation {
    DatabaseSecretValidation(Environment environment) {
        String password = environment.getProperty('spring.datasource.password')
        if (password == null || password.length() < 24 || password.toLowerCase(Locale.ROOT).contains('change-me'))
            throw new IllegalArgumentException('Configure a generated database secret of at least 24 characters using scripts/setup.')
    }
}
