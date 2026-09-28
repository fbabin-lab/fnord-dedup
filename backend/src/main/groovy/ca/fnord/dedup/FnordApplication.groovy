package ca.fnord.dedup

import ca.fnord.dedup.security.PasswordHashCommand
import groovy.transform.CompileStatic
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan

@CompileStatic
@SpringBootApplication
@ConfigurationPropertiesScan
class FnordApplication {
    static void main(String[] args) {
        if (args.length == 1 && args[0] == '--hash-password-stdin') {
            PasswordHashCommand.run(System.in, System.out)
            return
        }
        SpringApplication.run(FnordApplication, args)
    }
}
