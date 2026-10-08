/*
 * TermDeb ADB bridge — PKCS#8 RSA (n, e) extraction tests (plain JVM).
 *
 * WirelessTls.pkcs8RsaModulusExponent exists because neither
 * RSAPrivateCrtKey casts nor KeyFactory.getKeySpec(RSAPrivateCrtKeySpec.class)
 * are provider-agnostic (Conscrypt returns opaque PrivateKeys; the JDK
 * KeyFactory casts). These tests run without Robolectric so the parser is
 * exercised locally, not only on CI.
 */
package com.termux.app.adb.wireless;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;

public class Pkcs8RsaParseTest {

    @Test
    public void extractsModulusAndExponentFromPkcs8() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();

        byte[] pkcs8 = kp.getPrivate().getEncoded();
        assertNotNull("JDK private keys must expose a PKCS#8 encoding", pkcs8);

        BigInteger[] ne = WirelessTls.pkcs8RsaModulusExponent(pkcs8);
        RSAPublicKey expected = (RSAPublicKey) kp.getPublic();
        assertEquals("modulus", expected.getModulus(), ne[0]);
        assertEquals("public exponent", expected.getPublicExponent(), ne[1]);
    }

    @Test
    public void reconstructedPublicKeyIsUsableForSigning() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();

        BigInteger[] ne = WirelessTls.pkcs8RsaModulusExponent(
            kp.getPrivate().getEncoded());
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPublicKey rebuilt = (RSAPublicKey) kf.generatePublic(
            new RSAPublicKeySpec(ne[0], ne[1]));

        // The rebuilt key must equal the original and be MiniCert-compatible
        // (the TLS identity path depends on both).
        assertEquals(kp.getPublic(), rebuilt);
        java.security.cert.X509Certificate cert = MiniCert.generate(
            "pkcs8-parse-test", rebuilt, kp.getPrivate());
        assertNotNull(cert);
        assertEquals(rebuilt, cert.getPublicKey());
    }

    @Test
    public void rejectsGarbageAndTruncatedInput() {
        for (byte[] bad : new byte[][]{
            new byte[0],
            new byte[]{0x30},
            new byte[]{0x30, 0x10, 0x02, 0x01, 0x00},  // truncated contents
            new byte[]{0x02, 0x01, 0x00},               // not a SEQUENCE
            "not der at all".getBytes(),
        }) {
            try {
                WirelessTls.pkcs8RsaModulusExponent(bad);
                fail("must reject malformed input: length " + bad.length);
            } catch (java.security.GeneralSecurityException expected) {
                // Honest rejection — no partial results.
            }
        }
        try {
            WirelessTls.pkcs8RsaModulusExponent(null);
            fail("must reject null");
        } catch (java.security.GeneralSecurityException expected) {
            // Honest rejection.
        }
    }

    @Test
    public void rejectsNonRsaAlgorithmIdentifier() throws Exception {
        // Build a PKCS#8-like structure with an EC OID instead of rsaEncryption.
        byte[] ecOid = new byte[]{0x06, 0x07, 0x2A, (byte) 0x86, 0x48,
            (byte) 0xCE, 0x3D, 0x02, 0x01}; // 1.2.840.10045.2.1 (id-ecPublicKey)
        byte[] algId = der(0x30, ecOid);
        byte[] version = der(0x02, new byte[]{0});
        byte[] inner = der(0x30, concat(concat(version, algId),
            der(0x04, der(0x30, new byte[8]))));
        try {
            WirelessTls.pkcs8RsaModulusExponent(inner);
            fail("non-RSA AlgorithmIdentifier must be rejected");
        } catch (java.security.GeneralSecurityException expected) {
            // Honest rejection.
        }
    }

    // ---- tiny DER helpers ----

    private static byte[] der(int tag, byte[] contents) {
        byte[] len;
        if (contents.length < 0x80) {
            len = new byte[]{(byte) contents.length};
        } else if (contents.length < 0x100) {
            len = new byte[]{(byte) 0x81, (byte) contents.length};
        } else {
            len = new byte[]{(byte) 0x82,
                (byte) (contents.length >>> 8), (byte) contents.length};
        }
        return concat(concat(new byte[]{(byte) tag}, len), contents);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
