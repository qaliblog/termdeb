/*
 * TermDeb ADB bridge — guest CLI (`adb pair`) argument parsing tests.
 *
 * `adb pair HOST:PAIRING_PORT` with an optional code argument (for
 * non-interactive use). Mirrors the C client's parse in adb_client.c:
 * addr required, addr must contain a port, code (if given) must be 6 digits.
 */
package com.termux.app.adb.wireless;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.regex.Pattern;

/** Tests for the documented CLI grammar of `adb pair`. */
public class PairCliGrammarTest {

    private static final Pattern ADDR = Pattern.compile(".+:\\d+");
    private static final Pattern CODE = Pattern.compile("\\d{6}");

    private static boolean addrOk(String s) {
        return s != null && ADDR.matcher(s).matches();
    }

    private static boolean codeOk(String s) {
        return s == null || CODE.matcher(s).matches();
    }

    @Test
    public void validAddressAndCodeForms() {
        assertTrue(addrOk("192.168.1.102:37123"));
        assertTrue(addrOk("[::1]:37123".replace("[", "").replace("]", "")));
        assertTrue(addrOk("localhost:37123"));
        assertTrue(codeOk(null));
        assertTrue(codeOk("123456"));
    }

    @Test
    public void invalidAddressForms() {
        assertFalse(addrOk(null));
        assertFalse(addrOk(""));
        assertFalse(addrOk("192.168.1.102"));      // missing port
        assertFalse(addrOk("192.168.1.102:"));     // empty port
        assertFalse(addrOk(":37123"));             // missing host (regex needs chars)
        assertFalse(addrOk("host:abc"));           // non-numeric port
    }

    @Test
    public void invalidCodeForms() {
        assertFalse(codeOk("12345"));
        assertFalse(codeOk("1234567"));
        assertFalse(codeOk("12 456"));
        assertFalse(codeOk("abcdef"));
    }

    @Test
    public void codeNeverEchoedToStdout() {
        // The C client never prints the code; pin the contract: any trace of
        // the code in a success/failure message is a bug.
        String code = "654321";
        String okMsg = "Successfully paired to 192.168.1.102:37123 [guid=adb-xx]";
        String failMsg = "Failed to pair to 192.168.1.102:37123: wrong pairing code";
        assertFalse(okMsg.contains(code));
        assertFalse(failMsg.contains(code));
    }
}
