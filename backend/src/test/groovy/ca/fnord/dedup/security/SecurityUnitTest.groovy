package ca.fnord.dedup.security

import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import static org.junit.jupiter.api.Assertions.*
import java.time.*

class SecurityUnitTest {
    @Test void refusesMissingPlaintextLowCostAndPlaceholderHashes() {
        def encoder = new BCryptPasswordEncoder(12)
        for (String hash : [null,'change-me',new BCryptPasswordEncoder(4).encode('strong-test-password')]) {
            assertThrows(IllegalArgumentException) { SecurityConfiguration.validateCredentials(new SecurityProperties(passwordHash:hash),encoder) }
        }
        assertThrows(IllegalArgumentException) {
            SecurityConfiguration.validateCredentials(new SecurityProperties(passwordHash:encoder.encode('changeme')),encoder)
        }
    }
    @Test void rateLimitDoesNotTrustRequestIdentity() {
        def limiter = new LoginRateLimiter(null,Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'),ZoneOffset.UTC))
        5.times { assertTrue(limiter.acquire()) }
        assertFalse(limiter.acquire())
    }
    @Test void passwordToolRejectsBcryptTruncationAndWeakLengths() {
        for (String password : ['short','x'*73,'x'*14+'\n'])
            assertThrows(IllegalArgumentException) { PasswordHashCommand.run(new ByteArrayInputStream(password.bytes),new PrintStream(new ByteArrayOutputStream())) }
    }
}
