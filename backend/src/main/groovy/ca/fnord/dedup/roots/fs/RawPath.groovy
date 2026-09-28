package ca.fnord.dedup.roots.fs

import groovy.transform.CompileStatic
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

@CompileStatic
final class RawPath {
    static List<byte[]> components(byte[] path) {
        if (path == null) throw new IllegalArgumentException('A relative byte path is required.')
        List<byte[]> parts = new ArrayList<>()
        if (path.length == 0) return parts
        int start = 0
        for (int i = 0; i <= path.length; i++) {
            if (i < path.length && path[i] == 0) throw new IllegalArgumentException('NUL is not a path byte.')
            if (i == path.length || path[i] == 47) {
                byte[] part = Arrays.copyOfRange(path, start, i)
                if (part.length == 0 || part.length > 255 ||
                    (part.length == 1 && part[0] == 46) ||
                    (part.length == 2 && part[0] == 46 && part[1] == 46)) {
                    throw new IllegalArgumentException('Absolute, empty, dot or oversized path components are not allowed.')
                }
                parts.add(part)
                start = i + 1
            }
        }
        return parts
    }

    // A display representation is deliberately not a reversible filesystem path.
    static String display(byte[] path) {
        String decoded
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(path)).toString()
        } catch (CharacterCodingException ignored) {
            StringBuilder raw = new StringBuilder('[bytes] ')
            for (byte b : path) {
                int n = Byte.toUnsignedInt(b)
                if (n >= 32 && n < 127 && n != 92) raw.append((char)n)
                else raw.append(String.format('\\x%02x', n))
            }
            return raw.toString()
        }
        StringBuilder safe = new StringBuilder()
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i)
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || c == (char)92)
                safe.append(String.format('\\u%04x', (int)c))
            else safe.append(c)
        }
        return safe.toString()
    }
}
