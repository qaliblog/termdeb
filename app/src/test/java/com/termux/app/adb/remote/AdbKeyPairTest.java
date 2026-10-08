package com.termux.app.adb.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Unit tests for the Android ADB public-key format and RSA token signing. */
public class AdbKeyPairTest {

    private static AdbKeyPair generate() throws Exception {
        return AdbKeyPair.generate();
    }

    @Test
    public void testGeneratedKeySignsAndVerifies() throws Exception {
        AdbKeyPair pair = generate();
        byte[] token = new byte[20];
        for (int i = 0; i < token.length; i++) token[i] = (byte) i;

        byte[] sig = pair.signToken(token);
        assertEquals("AOSP RSA signatures are 256 bytes for a 2048-bit key",
            256, sig.length);

        // Verify with the public key recovered from the PKCS#8 roundtrip.
        AdbKeyPair reloaded = AdbKeyPair.load(pair.getPrivateKeyPkcs8());
        Signature verifier = Signature.getInstance("SHA1withRSA");
        RSAPublicKey pub = publicOf(reloaded);
        verifier.initVerify(pub);
        verifier.update(token);
        assertTrue("signature must verify against the derived public key",
            verifier.verify(sig));
    }

    @Test
    public void testAndroidPubkeyStructLayout() throws Exception {
        AdbKeyPair pair = generate();
        String b64 = pair.getAndroidPubkeyBase64();
        byte[] struct = java.util.Base64.getDecoder().decode(b64);

        assertEquals("Android pubkey struct for 2048-bit keys is 524 bytes",
            AdbKeyPair.ANDROID_PUBKEY_ENCODED_SIZE, struct.length);

        ByteBuffer buf = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN);
        int len = buf.getInt();
        assertEquals("len field is key size in 32-bit words", 64, len);

        int n0inv = buf.getInt();
        RSAPublicKey pub = publicOf(pair);
        BigInteger modulus = pub.getModulus();
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        // n0inv = -n^-1 mod 2^32
        BigInteger expected = modulus.modInverse(two32).negate().mod(two32);
        assertEquals("n0inv must be the Montgomery parameter",
            expected.intValue(), n0inv);

        // n[64] little-endian words.
        byte[] nLe = new byte[256];
        buf.get(nLe);
        byte[] nBe = stripLeadingZeroes(modulus.toByteArray());
        for (int i = 0; i < nBe.length; i++) {
            assertEquals("modulus must appear little-endian",
                nBe[nBe.length - 1 - i] & 0xff, nLe[i] & 0xff);
        }

        // Skip rr[64]; exponent is the last uint32.
        buf.position(buf.position() + 256);
        int exponent = buf.getInt();
        assertEquals(65537, exponent);
    }

    @Test
    public void testPubkeyLineFormat() throws Exception {
        AdbKeyPair pair = generate();
        String line = pair.getAndroidPubkeyLine("termdeb");
        // AOSP wire format (rsa_2048_key.cpp CalculatePublicKey + auth.cpp
        // send_auth_publickey): "<base64> <user@host>\0" — base64 FIRST,
        // single space, NUL-terminated, no trailing space.
        assertTrue("line must start with the base64 key",
            !line.startsWith(" ") && Character.isLetterOrDigit(line.charAt(0)));
        assertTrue("line must end with \"termdeb\\0\"", line.endsWith("termdeb\0"));
        int sp = line.indexOf(' ');
        assertTrue("exactly one separating space", sp > 0
            && line.indexOf(' ', sp + 1) < 0);
        String b64 = line.substring(0, sp);
        assertEquals(700, b64.length()); // 524 bytes -> 700 base64 chars (one '=')
        for (int i = 0; i < b64.length(); i++) {
            char c = b64.charAt(i);
            boolean pad = c == '=' && i >= b64.length() - 2;
            assertTrue("standard base64 alphabet only: " + c, pad
                || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == '+' || c == '/');
        }
        // The base64 part must decode to the 524-byte Android struct.
        byte[] struct = java.util.Base64.getDecoder().decode(b64);
        assertEquals(AdbKeyPair.ANDROID_PUBKEY_ENCODED_SIZE, struct.length);
    }

    @Test
    public void testLoadRoundtripProducesSamePubkey() throws Exception {
        AdbKeyPair pair = generate();
        AdbKeyPair reloaded = AdbKeyPair.load(pair.getPrivateKeyPkcs8());
        assertEquals("same key material must encode to the same Android pubkey",
            pair.getAndroidPubkeyBase64(), reloaded.getAndroidPubkeyBase64());
        assertEquals(pair.getFingerprint(), reloaded.getFingerprint());
        assertNotNull(reloaded.getFingerprint());
        assertEquals("AOSP fingerprints are 20 bytes", 59,
            reloaded.getFingerprint().length()); // 20*2 hex + 19 colons
    }

    private static RSAPublicKey publicOf(AdbKeyPair pair) throws Exception {
        // Recover the public key by re-deriving it through load(), which is the
        // only place the RSAPublicKey is exposed to the test.
        AdbKeyPair reloaded = AdbKeyPair.load(pair.getPrivateKeyPkcs8());
        byte[] struct = java.util.Base64.getDecoder()
            .decode(reloaded.getAndroidPubkeyBase64());
        return reconstructedPublicKey(struct);
    }

    /** Rebuild an RSAPublicKey from the Android struct (for verification). */
    private static RSAPublicKey reconstructedPublicKey(byte[] struct) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN);
        int len = buf.getInt();
        int n0inv = buf.getInt();
        byte[] nLe = new byte[len * 4];
        buf.get(nLe);
        byte[] rr = new byte[len * 4];
        buf.get(rr);
        int e = buf.getInt();

        BigInteger modulus = new BigInteger(1, reverse(nLe));
        BigInteger exponent = BigInteger.valueOf(e);

        // Sanity: the struct must be self-consistent with itself.
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        assertEquals(n0inv, modulus.modInverse(two32).negate().mod(two32).intValue());

        return (RSAPublicKey) KeyFactory.getInstance("RSA")
            .generatePublic(new java.security.spec.RSAPublicKeySpec(modulus, exponent));
    }

    private static byte[] reverse(byte[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[in.length - 1 - i];
        }
        return out;
    }

    private static byte[] stripLeadingZeroes(byte[] in) {
        int i = 0;
        while (i < in.length - 1 && in[i] == 0) i++;
        byte[] out = new byte[in.length - i];
        System.arraycopy(in, i, out, 0, out.length);
        return out;
    }
}
