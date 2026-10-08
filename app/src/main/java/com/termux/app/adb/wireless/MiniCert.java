/*
 * TermDeb ADB bridge — minimal self-signed X.509 v3 certificate builder.
 *
 * AOSP's adb generates the Wireless Debugging TLS identity by self-signing a
 * certificate for the ADB RSA key (adb_wifi.cpp GenerateX509Certificate).
 * Android exposes no public API for building X.509 certificates, and pulling
 * a full PKI library for one self-signed cert is out of proportion, so this
 * encodes the exact DER structure by hand:
 *
 *   Certificate ::= SEQUENCE {
 *     tbsCertificate SEQUENCE {
 *       [0] version v3, serialNumber, signature (sha256WithRSAEncryption),
 *       issuer, validity, subject, subjectPublicKeyInfo },
 *     signatureAlgorithm, signatureValue }
 *
 * The output is verified in the unit tests with the standard JVM
 * CertificateFactory (parse + signature + validity), which exercises the same
 * DER grammar Conscrypt parses on-device.
 */
package com.termux.app.adb.wireless;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Calendar;
import java.util.TimeZone;

final class MiniCert {

    private MiniCert() {
    }

    /** Build and sign a self-signed v3 certificate for an RSA key pair. */
    static X509Certificate generate(String commonName, RSAPublicKey publicKey,
                                    PrivateKey privateKey)
        throws java.security.GeneralSecurityException, java.io.IOException {
        long notBefore = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        Calendar notAfter = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        notAfter.add(Calendar.YEAR, 20); // within UTCTime range (<= 2049)

        ByteArrayOutputStream tbs = new ByteArrayOutputStream();
        byte[] version = der(INTEGER, new byte[]{2}); // v3 inside [0] EXPLICIT
        byte[] versionWrapped = der((byte) 0xA0, version);
        byte[] serial = derInteger(BigInteger.valueOf(System.currentTimeMillis() / 1000L));
        byte[] sigAlg = sha256RsaAlgorithm();
        byte[] name = commonNameRdn(commonName);
        byte[] validity = der(SEQUENCE,
            concat(utcTime(notBefore), utcTime(notAfter.getTimeInMillis())));
        byte[] spki = rsaSpki(publicKey);

        tbs.write(versionWrapped);
        tbs.write(serial);
        tbs.write(sigAlg);
        tbs.write(name);
        tbs.write(validity);
        tbs.write(name); // issuer == subject (self-signed)
        tbs.write(spki);
        byte[] tbsDer = der(SEQUENCE, tbs.toByteArray());

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(tbsDer);
        byte[] signature = signer.sign();

        ByteArrayOutputStream cert = new ByteArrayOutputStream();
        cert.write(tbsDer);
        cert.write(sigAlg);
        cert.write(der(BIT_STRING, concat(new byte[]{0}, signature)));
        return CertificateFactoryHolder.parse(der(SEQUENCE, cert.toByteArray()));
    }

    // ---- algorithm identifiers ----

    private static byte[] sha256RsaAlgorithm() throws IOException {
        return der(SEQUENCE, concat(oid("1.2.840.113549.1.1.11"), der(NULL, new byte[0])));
    }

    private static byte[] rsaEncryptionAlgorithm() throws IOException {
        return der(SEQUENCE, concat(oid("1.2.840.113549.1.1.1"), der(NULL, new byte[0])));
    }

    /** Name ::= SEQUENCE of SET of SEQUENCE (oid, value). CN = 2.5.4.3. */
    private static byte[] commonNameRdn(String cn) throws IOException {
        byte[] atv = der(SEQUENCE, concat(oid("2.5.4.3"),
            der(PRINTABLE_STRING, ascii(cn))));
        return der(SEQUENCE, der(SET, atv));
    }

    /** SubjectPublicKeyInfo for an RSA key. */
    private static byte[] rsaSpki(RSAPublicKey key) throws IOException {
        byte[] n = key.getModulus().toByteArray(); // signed; leading 0x00 OK in INTEGER
        byte[] e = key.getPublicExponent().toByteArray();
        byte[] rsaKey = der(SEQUENCE, concat(der(INTEGER, n), der(INTEGER, e)));
        byte[] bitString = der(BIT_STRING, concat(new byte[]{0}, rsaKey));
        return der(SEQUENCE, concat(rsaEncryptionAlgorithm(), bitString));
    }

    private static byte[] utcTime(long epochMs) throws IOException {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(epochMs);
        // UTCTime: YYMMDDHHMMSSZ
        String s = String.format("%02d%02d%02d%02d%02d%02dZ",
            cal.get(Calendar.YEAR) % 100,
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND));
        return der(UTC_TIME, ascii(s));
    }

    // ---- DER primitives ----

    private static final byte SEQUENCE = 0x30;
    private static final byte SET = 0x31;
    private static final byte INTEGER = 0x02;
    private static final byte BIT_STRING = 0x03;
    private static final byte NULL = 0x05;
    private static final byte PRINTABLE_STRING = 0x13;
    private static final byte UTC_TIME = 0x17;

    private static byte[] der(byte tag, byte[] value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeLength(out, value.length);
        out.write(value);
        return out.toByteArray();
    }

    private static byte[] derInteger(BigInteger v) throws IOException {
        return der(INTEGER, v.toByteArray());
    }

    private static void writeLength(ByteArrayOutputStream out, int len) throws IOException {
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else {
            out.write(0x82);
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
        }
    }

    /** Encode a dotted OID string to DER content bytes (X.690 8.19). */
    private static byte[] oid(String dotted) throws IOException {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            long value = Long.parseLong(parts[i]);
            byte[] stack = new byte[9];
            int n = 0;
            stack[n++] = (byte) (value & 0x7f);
            value >>= 7;
            while (value > 0) {
                stack[n++] = (byte) ((value & 0x7f) | 0x80);
                value >>= 7;
            }
            for (int k = n - 1; k >= 0; k--) body.write(stack[k]);
        }
        return der((byte) 0x06, body.toByteArray());
    }

    private static byte[] ascii(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 127) throw new IllegalArgumentException("non-ASCII");
            out[i] = (byte) c;
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Small indirection so the class stays free of javax imports in tests too. */
    private static final class CertificateFactoryHolder {
        static X509Certificate parse(byte[] der)
            throws java.security.cert.CertificateException, java.io.IOException {
            return (X509Certificate) java.security.cert.CertificateFactory
                .getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der));
        }
    }
}
