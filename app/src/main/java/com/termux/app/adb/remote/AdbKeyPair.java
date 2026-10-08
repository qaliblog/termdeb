/*
 * TermDeb ADB bridge — Android ADB public key format and RSA signing.
 *
 * ADB uses the Android ADB public key format: a 2048-bit RSAPublicKey struct
 * (Android-specific, LE fields: len, n0inv, n[64], rr[64], exponent),
 * base64-encoded and sent on the wire as "<base64> <user@host>\0"
 * (AOSP rsa_2048_key.cpp CalculatePublicKey + client/auth.cpp send_auth_publickey
 * which appends the terminating '\0'). Verification against
 * AOSP packages/modules/adb, crypto/key.cpp (android_pubkey_encode) and
 * crypto/android_pubkey.h. Only used to sign A_AUTH tokens for real remote
 * adbd connections — the security boundary stays Android's own (adbd decides
 * whether our key is authorized; the user confirms the RSA fingerprint dialog
 * or pairs via Wireless Debugging).
 *
 * Implementation notes:
 * - android_pubkey_encode is re-implemented here on top of the platform
 *   java.security API (no conscrypt internals needed): it needs modpow on the
 *   modulus to compute rr = 2^(4096) mod n and n0inv = -n^-1 mod 2^32.
 * - Tokens are signed with PKCS#1 v1.5 SHA-1 (AOSP kSignver = SHA-1).
 */
package com.termux.app.adb.remote;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/** Android ADB RSA key material: generation, encoding, signing, storage. */
public final class AdbKeyPair {

    /** Android pubkey struct for 2048-bit modulus: 4 + 4 + 256 + 256 + 4. */
    public static final int ANDROID_PUBKEY_ENCODED_SIZE = 524;

    private static final int KEY_SIZE_BITS = 2048;

    private final PrivateKey mPrivateKey;
    private final byte[] mAndroidPubkey;

    private AdbKeyPair(PrivateKey privateKey, byte[] androidPubkey) {
        mPrivateKey = privateKey;
        mAndroidPubkey = androidPubkey;
    }

    /** PKCS#8 DER of the private key (for storage). */
    public byte[] getPrivateKeyPkcs8() {
        return mPrivateKey.getEncoded();
    }

    /**
     * Public key payload exactly as AOSP puts it on the wire:
     * "<base64> <user@host>\0".
     */
    public String getAndroidPubkeyLine(String userhost) {
        return base64Encode(mAndroidPubkey) + " " + userhost + "\0";
    }

    /** Base64 of the Android pubkey struct (no line breaks). */
    public String getAndroidPubkeyBase64() {
        return base64Encode(mAndroidPubkey);
    }

    /** The SHA-1 fingerprint of the public key, AOSP-style colon-hex. */
    public String getFingerprint() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(mAndroidPubkey);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sign an A_AUTH token (RSASSA-PKCS1-v1_5 with SHA-1, AOSP kSignver). */
    public byte[] signToken(byte[] token) throws Exception {
        Signature sig = Signature.getInstance("SHA1withRSA");
        sig.initSign(mPrivateKey);
        sig.update(token);
        return sig.sign();
    }

    // ---------- storage ----------

    public static AdbKeyPair load(byte[] pkcs8) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        PublicKey pub = derivePublic(kf, priv);
        return new AdbKeyPair(priv, encodeAndroidPubkey((RSAPublicKey) pub));
    }

    public static AdbKeyPair generate() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(KEY_SIZE_BITS, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();
        return new AdbKeyPair(kp.getPrivate(),
            encodeAndroidPubkey((RSAPublicKey) kp.getPublic()));
    }

    private static PublicKey derivePublic(KeyFactory kf, PrivateKey priv)
        throws InvalidKeySpecException {
        // PKCS#8 RSA private keys are CRT keys carrying the public exponent
        // and modulus; recover the public key through RSAPublicKeySpec.
        java.security.interfaces.RSAPrivateCrtKey crtPriv =
            (java.security.interfaces.RSAPrivateCrtKey) priv;
        return kf.generatePublic(new java.security.spec.RSAPublicKeySpec(
            crtPriv.getModulus(), crtPriv.getPublicExponent()));
    }

    // ---------- android_pubkey_encode ----------

    /**
     * Encode an RSA public key in Android's ADB public key format
     * (AOSP crypto/key.cpp android_pubkey_encode).
     */
    static byte[] encodeAndroidPubkey(RSAPublicKey key) throws Exception {
        BigInteger n = key.getModulus();
        BigInteger e = key.getPublicExponent();

        ByteBuffer buf = ByteBuffer.allocate(ANDROID_PUBKEY_ENCODED_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // len = key size in words (uint32).
        int words = n.bitLength() / 32;
        buf.putInt(words);

        // n0inv = -n^{-1} mod 2^32 (Montgomery parameter n0inv, uint32).
        BigInteger montgomeryN0 = n.modInverse(BigInteger.ONE.shiftLeft(32))
            .negate().mod(BigInteger.ONE.shiftLeft(32));
        buf.putInt(montgomeryN0.intValue());

        // n[words] little-endian words.
        byte[] nBytes = stripLeadingZeroes(n.toByteArray());
        buf.put(toLeWords(nBytes, words * 4));

        // rr = (2^(rsanumwords*32))^2 mod n, Montgomery context.
        BigInteger rr = BigInteger.TWO.modPow(
            BigInteger.valueOf(words * 4L * 8), n)
            .modPow(BigInteger.TWO, n);
        byte[] rrBytes = stripLeadingZeroes(rr.toByteArray());
        buf.put(toLeWords(rrBytes, words * 4));

        // exponent (uint32).
        buf.putInt(e.intValue());

        byte[] out = new byte[ANDROID_PUBKEY_ENCODED_SIZE];
        buf.flip();
        buf.get(out);
        return out;
    }

    private static byte[] stripLeadingZeroes(byte[] in) {
        int i = 0;
        while (i < in.length - 1 && in[i] == 0) i++;
        byte[] out = new byte[in.length - i];
        System.arraycopy(in, i, out, 0, out.length);
        return out;
    }

    /** Right-align a big-endian magnitude into a fixed little-endian buffer. */
    private static byte[] toLeWords(byte[] be, int outLen) {
        byte[] out = new byte[outLen];
        int inLen = be.length;
        for (int i = 0; i < inLen && i < outLen; i++) {
            out[i] = be[inLen - 1 - i];
        }
        return out;
    }

    static String base64Encode(byte[] data) {
        return Base64.noWrap().encode(data);
    }

    /** Minimal Base64 (RFC 4648, no wrapping) — mirrors libcrypto b64. */
    static final class Base64 {
        private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

        static Base64 noWrap() {
            return new Base64();
        }

        String encode(byte[] data) {
            StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
            int i = 0;
            for (; i + 2 < data.length; i += 3) {
                int v = (data[i] & 0xff) << 16 | (data[i + 1] & 0xff) << 8 | (data[i + 2] & 0xff);
                sb.append(ALPHABET[(v >> 18) & 63]).append(ALPHABET[(v >> 12) & 63])
                    .append(ALPHABET[(v >> 6) & 63]).append(ALPHABET[v & 63]);
            }
            int rem = data.length - i;
            if (rem == 1) {
                int v = (data[i] & 0xff) << 16;
                sb.append(ALPHABET[(v >> 18) & 63]).append(ALPHABET[(v >> 12) & 63]).append("==");
            } else if (rem == 2) {
                int v = (data[i] & 0xff) << 16 | (data[i + 1] & 0xff) << 8;
                sb.append(ALPHABET[(v >> 18) & 63]).append(ALPHABET[(v >> 12) & 63])
                    .append(ALPHABET[(v >> 6) & 63]).append('=');
            }
            return sb.toString();
        }
    }

}
