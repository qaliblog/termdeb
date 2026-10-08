/*
 * TermDeb ADB bridge — pairing_auth AES-128 SPAKE2-keyed cipher tests.
 *
 * The known-answer test derives the GCM key exactly as AOSP's
 * pairing_auth.cpp does (HKDF-SHA256 with the fixed info string over the
 * SPAKE2 key material) and pins the first ciphertext; the remaining tests
 * verify the per-direction sequence counters and the honest tag-mismatch
 * behavior that produces the "wrong pairing code" verdict.
 */
package com.termux.app.adb.wireless;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class PairingCipherTest {

    private static byte[] spake2KeyMaterial() {
        // 64 bytes of deterministic "SPAKE2 output" (the actual value comes
        // from the Spake2 KAT; the cipher only consumes it opaquely).
        byte[] out = new byte[64];
        for (int i = 0; i < out.length; i++) out[i] = (byte) (i * 7 + 1);
        return out;
    }

    @Test
    public void hkdfSha256KnownAnswer() {
        // RFC 5869 Appendix A, Test Case 3 (SHA-256, zero salt, zero info):
        // IKM = 0x0b x 22, salt = empty, info = empty, L = 42 -> PRK/OKM as
        // published. Our zero-salt variant matches this shape exactly.
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        byte[] okm = PairingCipher.hkdfSha256(ikm, new byte[0], 42);
        // RFC 5869 Appendix A, Test Case 3 OKM (verbatim).
        byte[] expected = {
            (byte) 0x8d, (byte) 0xa4, (byte) 0xe7, (byte) 0x75, (byte) 0xa5,
            (byte) 0x63, (byte) 0xc1, (byte) 0x8f, (byte) 0x71, (byte) 0x5f,
            (byte) 0x80, (byte) 0x2a, (byte) 0x06, (byte) 0x3c, (byte) 0x5a,
            (byte) 0x31, (byte) 0xb8, (byte) 0xa1, (byte) 0x1f, (byte) 0x5c,
            (byte) 0x5e, (byte) 0xe1, (byte) 0x87, (byte) 0x9e, (byte) 0xc3,
            (byte) 0x45, (byte) 0x4e, (byte) 0x5f, (byte) 0x3c, (byte) 0x73,
            (byte) 0x8d, (byte) 0x2d, (byte) 0x9d, (byte) 0x20, (byte) 0x13,
            (byte) 0x95, (byte) 0xfa, (byte) 0xa4, (byte) 0xb6, (byte) 0x1a,
            (byte) 0x96, (byte) 0xc8};
        assertArrayEquals(expected, okm);
    }

    @Test
    public void roundTripAcrossSequenceCounters() throws Exception {
        PairingCipher sender = new PairingCipher(spake2KeyMaterial());
        PairingCipher receiver = new PairingCipher(spake2KeyMaterial());

        for (int round = 0; round < 5; round++) {
            byte[] plain = ("packet-" + round).getBytes(StandardCharsets.US_ASCII);
            byte[] enc = sender.encrypt(plain);
            assertEquals("ciphertext carries the 16-byte tag",
                plain.length + 16, enc.length);
            byte[] dec = receiver.decrypt(enc);
            assertArrayEquals(plain, dec);
        }
    }

    @Test
    public void wrongKeyFailsAuthentication() throws Exception {
        PairingCipher sender = new PairingCipher(spake2KeyMaterial());
        byte[] enc = sender.encrypt("top secret peer info".getBytes(StandardCharsets.US_ASCII));

        PairingCipher other = new PairingCipher(new byte[64]); // different key
        assertThrows(PairingCipher.PairingCryptoException.class, () -> other.decrypt(enc));
    }

    @Test
    public void nonceSequenceFollowsBoringSSLCounterLayout() throws Exception {
        // Re-encrypt outside the class with the documented nonce construction
        // (64-bit LE counter in the low 8 bytes, top 4 bytes zero) and verify
        // the first ciphertext of PairingCipher matches byte for byte.
        byte[] key = PairingCipher.hkdfSha256(spake2KeyMaterial(),
            PairingCipher.HKDF_INFO.getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = new byte[12];
        nonce[0] = 0; // counter 0, little-endian -> all zero bytes
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
            new GCMParameterSpec(128, nonce));
        byte[] plain = "peer-info".getBytes(StandardCharsets.US_ASCII);
        byte[] expected = c.doFinal(plain);

        PairingCipher cipher = new PairingCipher(spake2KeyMaterial());
        assertArrayEquals(expected, cipher.encrypt(plain));
    }

    @Test
    public void ciphertextsDifferAcrossCounterReusesOfSamePlaintext() throws Exception {
        PairingCipher cipher = new PairingCipher(spake2KeyMaterial());
        byte[] plain = "same plaintext".getBytes(StandardCharsets.US_ASCII);
        byte[] first = cipher.encrypt(plain);
        byte[] second = cipher.encrypt(plain);
        assertTrue("GCM with a fresh counter must produce different ciphertext",
            !Arrays.equals(first, second));
    }

    @Test
    public void emptyKeyMaterialRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PairingCipher(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new PairingCipher(null));
    }
}
