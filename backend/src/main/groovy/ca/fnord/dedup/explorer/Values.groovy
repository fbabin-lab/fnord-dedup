package ca.fnord.dedup.explorer

import ca.fnord.dedup.inventory.JobProblem
import groovy.transform.CompileStatic
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

@CompileStatic
final class Values {
    static void fields(Map body, Collection<String> allowed) {
        if (!allowed.containsAll(body.keySet())) invalid('Unknown request fields are not supported in this milestone.')
    }
    static void invalid(String detail) { throw new JobProblem(422,'INVALID_REQUEST',detail) }
    static UUID id(Object value) {
        try {
            if (!(value instanceof String) || !(value.toString() ==~ '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}')) throw new IllegalArgumentException()
            return UUID.fromString(value.toString())
        } catch (Exception ignored) { throw new JobProblem(422,'INVALID_IDENTIFIER','Use a UUID identifier.') }
    }
    static List<UUID> ids(Object value, int max=100, boolean empty=true) {
        if (!(value instanceof List) || ((List)value).size()>max || (!empty && ((List)value).isEmpty())) invalid('The selection exceeds its documented size bound.')
        List<UUID> result=new ArrayList<>()
        for (Object item : (List)value) result.add(id(item))
        if (new HashSet<UUID>(result).size()!=result.size()) invalid('Identifiers must be distinct.')
        result.sort { UUID a, UUID b -> a.toString().compareTo(b.toString()) }
        result
    }
    static String text(Object value,int max,boolean empty=true) {
        if (!(value instanceof String)) invalid('A text value is required.')
        String result=(String)value
        if ((!empty && result.isEmpty()) || result.codePointCount(0,result.length())>max || result.indexOf(0)>=0) invalid('Text is empty, too long or contains NUL.')
        for (int i=0;i<result.length();i++) {
            char c=result.charAt(i)
            if (Character.isHighSurrogate(c)) {
                if (++i>=result.length() || !Character.isLowSurrogate(result.charAt(i))) invalid('Text must be valid Unicode.')
            } else if (Character.isLowSurrogate(c)) invalid('Text must be valid Unicode.')
        }
        result
    }
    static long decimal(Object value) {
        if (!(value instanceof String) || !(value.toString() ==~ '0|[1-9][0-9]{0,18}')) invalid('Use an unsigned decimal string within signed 64-bit range.')
        try { return Long.parseLong(value.toString()) } catch (NumberFormatException ignored) { invalid('Decimal value exceeds signed 64-bit range.'); return 0L }
    }
    static String choice(Object value, Collection<String> choices) {
        if (!(value instanceof String) || !choices.contains(value)) invalid('Unsupported enumerated value.')
        (String)value
    }
    static String lookup(String label) { Normalizer.normalize(label,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT) }
    static String hash(String text) { HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(text.getBytes(StandardCharsets.UTF_8))) }
}
