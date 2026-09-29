package ca.fnord.dedup.security

import groovy.transform.CompileStatic
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.nio.charset.StandardCharsets

@CompileStatic
final class PasswordHashCommand {
    static void run(InputStream input, PrintStream output) {
        byte[] bytes = input.readNBytes(74)
        if (bytes.length < 14 || bytes.length > 72) throw new IllegalArgumentException('Use a password of 14 to 72 UTF-8 bytes.')
        String password = new String(bytes, StandardCharsets.UTF_8)
        if (password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0 || password.trim().length() < 14)
            throw new IllegalArgumentException('Password must contain at least 14 non-padding characters and no line breaks.')
        output.print(new BCryptPasswordEncoder(12).encode(password))
        Arrays.fill(bytes, (byte)0)
    }
}
