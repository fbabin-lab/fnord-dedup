package ca.fnord.dedup.signatures

import groovy.transform.CompileStatic
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import jakarta.validation.constraints.*

@CompileStatic
@Validated
@ConfigurationProperties('fnord.signatures')
class SignatureLimits {
    @Min(1024L) @Max(16777216L) int importBytes=1048576
    @Min(1L) @Max(10000L) int importRows=2000
    @Min(1024L) @Max(67108864L) int exportBytes=16777216
    @Min(1024L) long exportQuotaBytes=268435456L
}
