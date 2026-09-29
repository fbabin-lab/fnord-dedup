package ca.fnord.dedup.signatures

import ca.fnord.dedup.inventory.JobProblem
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import static org.junit.jupiter.api.Assertions.*

/** The UI clears stale overrides; the server remains authoritative for exact UTF-8/raw bytes. */
class SignatureFilenameTest {
    private static Map record(Map extra=[:]) {
        [name:'Benign fixture',sizeBytes:'5',algorithm:'SHA-256',checksum:'a'*64,
         filenameMatchMode:'REQUIRED_EXACT']+extra
    }
    @Test void clearedRawOverrideUsesTheNewUnicodeFilenameExactly() {
        for (String name : ['new.txt','renamed-é.txt','e\u0301.txt']) {
            Map result=SignatureCatalog.normalize(record([filename:name,filenameBytesBase64:null]))
            byte[] bytes=Base64.decoder.decode(result.filenameBytesBase64.toString())
            assertArrayEquals(name.getBytes(StandardCharsets.UTF_8),bytes)
            assertFalse(Arrays.equals('old.txt'.getBytes(StandardCharsets.UTF_8),bytes))
            assertEquals('REQUIRED_EXACT',result.filenameMatchMode)
        }
    }
    @Test void explicitNonUtf8OverrideRemainsAuthoritative() {
        Map result=SignatureCatalog.normalize(record([filename:'[bytes] \\xfb\\xff',filenameBytesBase64:'+/8=']))
        assertArrayEquals([(byte)0xfb,(byte)0xff] as byte[],Base64.decoder.decode(result.filenameBytesBase64.toString()))
    }
    @Test void clearingRequiredExactNameAndOverrideCannotRetainAnInvisibleOldRule() {
        assertThrows(JobProblem) { SignatureCatalog.normalize(record([filename:null,filenameBytesBase64:null])) }
    }
    @Test void clearingAnAdvisoryNameRemovesTheOptionalRawBytes() {
        Map result=SignatureCatalog.normalize(record([filenameMatchMode:'ADVISORY',filename:null,filenameBytesBase64:null]))
        assertNull(result.filename); assertNull(result.filenameBytesBase64)
    }
}
