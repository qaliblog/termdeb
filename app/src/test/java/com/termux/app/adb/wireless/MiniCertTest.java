/*
 * TermDeb ADB bridge — MiniCert (self-signed X.509 builder) tests.
 *
 * Parses the generated DER with the standard JVM CertificateFactory — the
 * same grammar Conscrypt parses on-device — and verifies the signature, the
 * subject, and the validity window.
 */
package com.termux.app.adb.wireless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.X509Certificate;

public class MiniCertTest {

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    @Test
    public void generatedCertParsesAndVerifies() throws Exception {
        KeyPair kp = rsaKeyPair();
        X509Certificate cert = MiniCert.generate("termdeb-test",
            (java.security.interfaces.RSAPublicKey) kp.getPublic(), kp.getPrivate());

        assertEquals("CN=termdeb-test", cert.getSubjectX500Principal().getName());
        assertEquals("CN=termdeb-test", cert.getIssuerX500Principal().getName());
        assertEquals(3, cert.getVersion()); // v3

        // Signature verifies against the enclosed public key.
        cert.verify(kp.getPublic());

        // Validity: notBefore in the past, notAfter ~20 years out.
        long now = System.currentTimeMillis();
        assertNotNull(cert.getNotBefore());
        assertTrue(cert.getNotBefore().getTime() <= now);
        assertTrue(cert.getNotAfter().getTime() > now + 10L * 365 * 24 * 3600 * 1000);
    }

    @Test
    public void signatureCoversTheTbsBytes() throws Exception {
        KeyPair kp = rsaKeyPair();
        X509Certificate cert = MiniCert.generate("termdeb-tbs",
            (java.security.interfaces.RSAPublicKey) kp.getPublic(), kp.getPrivate());

        // Tamper with a byte inside the subject CN ("termdeb-tbs"), which is
        // opaque string content inside the TBS — the certificate still parses,
        // but the signature can no longer verify.
        byte[] der = cert.getEncoded();
        byte[] needle = "termdeb-tbs".getBytes("US-ASCII");
        int at = indexOf(der, needle);
        assertTrue("subject CN must be present in the DER", at >= 0);
        der[at] ^= 0x01;
        X509Certificate tampered = (X509Certificate) java.security.cert.CertificateFactory
            .getInstance("X.509")
            .generateCertificate(new java.io.ByteArrayInputStream(der));
        try {
            tampered.verify(kp.getPublic());
            throw new AssertionError("tampered certificate must not verify");
        } catch (java.security.GeneralSecurityException expected) {
            // expected
        }
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    @Test
    public void differentKeysProduceDifferentCerts() throws Exception {
        KeyPair kp1 = rsaKeyPair();
        KeyPair kp2 = rsaKeyPair();
        X509Certificate c1 = MiniCert.generate("termdeb",
            (java.security.interfaces.RSAPublicKey) kp1.getPublic(), kp1.getPrivate());
        X509Certificate c2 = MiniCert.generate("termdeb",
            (java.security.interfaces.RSAPublicKey) kp2.getPublic(), kp2.getPrivate());
        assertNotNull(c1.getPublicKey());
        assertEquals(kp1.getPublic(), c1.getPublicKey());
        assertEquals(kp2.getPublic(), c2.getPublicKey());
    }
}
