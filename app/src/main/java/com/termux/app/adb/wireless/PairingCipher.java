/*
 * TermDeb ADB bridge — pairing_auth AES-128-GCM cipher.
 *
 * Mirrors AOSP packages/modules/adb/pairing_auth/aes_128_gcm.cpp exactly:
 *  - key = HKDF-SHA256(spake2_key, info="adb pairing_auth aes-128-gcm key",
 *    salt = 32 zero bytes, L=16)
 *  - per-direction 96-bit nonce = 64-bit little-endian counter, zero-based,
 *    in the low 8 bytes; the top 4 bytes are zero (BoringSSL EVP_AEAD
 *    convention adb relies on)
 *  - no AAD, 128-bit tag appended to the ciphertext
 *
 * Encryption and decryption sequence counters are independent (each side
 * encrypts with its own stream; both start at 0), matching the two
 * EVP_AEAD_CTX sequences in AOSP.
 */
package com.termux.app.adb.wireless;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class PairingCipher {

    static final String HKDF_INFO = "adb pairing_auth aes-128-gcm key";
    private static final int KEY_LEN = 16;
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec aesKey;
    private long encSequence;
    private long decSequence;

    PairingCipher(byte[] spake2KeyMaterial) {
        if (spake2KeyMaterial == null || spake2KeyMaterial.length == 0) {
            throw new IllegalArgumentException("empty key material");
        }
        byte[] key = hkdfSha256(spake2KeyMaterial,
            HKDF_INFO.getBytes(StandardCharsets.US_ASCII), KEY_LEN);
        this.aesKey = new SecretKeySpec(key, "AES");
    }

    /** Encrypt with the sender counter; returns ciphertext ‖ 16-byte tag. */
    byte[] encrypt(byte[] plaintext) throws PairingCryptoException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, nonce(encSequence)));
            byte[] out = c.doFinal(plaintext);
            encSequence++;
            return out;
        } catch (Exception e) {
            throw new PairingCryptoException("encrypt failed", e);
        }
    }

    /** Decrypt with the receiver counter; throws on tag mismatch. */
    byte[] decrypt(byte[] ciphertext) throws PairingCryptoException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, nonce(decSequence)));
            byte[] out = c.doFinal(ciphertext);
            decSequence++;
            return out;
        } catch (AEADBadTagException e) {
            throw new PairingCryptoException("authentication failed", e);
        } catch (Exception e) {
            throw new PairingCryptoException("decrypt failed", e);
        }
    }

    private static byte[] nonce(long sequence) {
        byte[] nonce = new byte[NONCE_LEN];
        for (int i = 0; i < 8; i++) {
            nonce[i] = (byte) (sequence >>> (8 * i));
        }
        // Top 4 bytes stay zero.
        return nonce;
    }

    /**
     * HKDF-SHA256 (RFC 5869) with a zero salt — extract, then expand with
     * the standard T(i) counter chain (multi-block for L > 32).
     */
    static byte[] hkdfSha256(byte[] ikm, byte[] info, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("negative HKDF output length");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256")); // zero salt
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] okm = new byte[length];
            byte[] t = new byte[0];
            int pos = 0;
            byte counter = 1;
            while (pos < length) {
                mac.update(t);
                mac.update(info);
                mac.update(counter);
                t = mac.doFinal();
                int n = Math.min(t.length, length - pos);
                System.arraycopy(t, 0, okm, pos, n);
                pos += n;
                counter++;
            }
            return okm;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Checked exception so protocol failures are explicit at call sites. */
    static final class PairingCryptoException extends Exception {
        PairingCryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
