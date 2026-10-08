/*
 * TermDeb ADB bridge — SPAKE2 for the Android 11+ Wireless Debugging pairing.
 *
 * AOSP's pairing_auth (packages/modules/adb/pairing_auth/pairing_auth.cpp)
 * delegates to BoringSSL's SPAKE2 (crypto/curve25519/spake25519.c) over the
 * Ed25519 group with the roles/names:
 *
 *   client -> alice, my name   = "adb pair client\0"
 *          -> peer name        = "adb pair server\0"
 *   server -> bob,   reversed
 *
 * This is an independent pure-Java implementation of that exact algorithm
 * (structure verified line-by-line against BoringSSL spake25519.c and the
 * Apache-2.0 Java reference port in github.com/xswl369/android-wireless-debug;
 * no GPL code involved). Points use twisted-Edwards projective coordinates
 * (X:Y:Z:T, a=-1); scalars use BigInteger. Not constant-time — acceptable
 * here because the SPAKE2 secret is a short-lived pairing code + TLS-exported
 * keying material, and the implementation runs only on the pairing path.
 *
 * Wire format: 32-byte Ed25519 point encodings. Shared secret: 64-byte
 * SHA-512 transcript key (SPAKE2_MAX_KEY_SIZE), consumed by
 * PairingConnection for the AES-128-GCM HKDF.
 */
package com.termux.app.adb.wireless;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

public final class Spake2 {

    public static final int ROLE_ALICE = 0; // the pairing CLIENT
    public static final int ROLE_BOB = 1;   // the pairing SERVER (device)

    public static final int MSG_SIZE = 32;
    public static final int MAX_KEY_SIZE = 64;

    // ---- Ed25519 group (RFC 8032) ----
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger D = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger TWO_D = D.shiftLeft(1).mod(P);
    /** Order of the prime-order subgroup. */
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
        .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2).modPow(
        P.subtract(BigInteger.ONE).shiftRight(2), P);

    // ---- SPAKE2 mask points M (alice) / N (bob) ----
    // Encoded values verified against BoringSSL spake25519.c header comments
    // (kSpakeMSmallPrecomp / kSpakeNSmallPrecomp first entries, little-endian).
    private static final BigInteger MX = leHex("c8a663c597f1ee40ab6242ee256f326c752ca7d3bd323b1e119cbd04a9786f45");
    private static final BigInteger MY = leHex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e");
    private static final BigInteger NX = leHex("201bc5b343177110441e73b3ae3fbf9ff544c8138fd101c28a1a6dea4d005d6e");
    private static final BigInteger NY = leHex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778");

    private static final byte[] BASE_POINT_ENC = new byte[32];

    static {
        // Ed25519 base point B: y = 4/5, x recovered odd; LE encoding 0x58,0x66*31.
        BASE_POINT_ENC[0] = 0x58;
        for (int i = 1; i < 32; i++) BASE_POINT_ENC[i] = (byte) 0x66;
    }

    private final int role;
    private final byte[] myName;
    private final byte[] theirName;
    private final SecureRandom random;

    private BigInteger privateKey;
    private BigInteger passwordScalar;
    private byte[] passwordHash;
    private byte[] myMsg;
    private int state;

    private Spake2(int role, byte[] myName, byte[] theirName, SecureRandom random) {
        this.role = role;
        this.myName = myName;
        this.theirName = theirName;
        this.random = random;
        this.state = 0;
    }

    /** The pairing CLIENT side (talks to the device's pairing server). */
    public static Spake2 newClient() {
        return new Spake2(ROLE_ALICE, b("adb pair client\0"), b("adb pair server\0"), new SecureRandom());
    }

    /** The pairing SERVER side (only used by tests' fake pairing endpoints). */
    public static Spake2 newServer() {
        return new Spake2(ROLE_BOB, b("adb pair server\0"), b("adb pair client\0"), new SecureRandom());
    }

    /** Test seam: explicit SecureRandom so protocol tests are deterministic. */
    public static Spake2 forTest(int role, SecureRandom random) {
        return new Spake2(role,
            role == ROLE_ALICE ? b("adb pair client\0") : b("adb pair server\0"),
            role == ROLE_ALICE ? b("adb pair server\0") : b("adb pair client\0"),
            random);
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** Generate our 32-byte SPAKE2 message from the (code ‖ exported-key) password. */
    public byte[] generateMsg(byte[] password) {
        byte[] rnd = new byte[64];
        random.nextBytes(rnd);
        return generateMsgWithRandom(password, rnd);
    }

    /** Deterministic variant for tests (rnd is reduced mod the group order). */
    byte[] generateMsgWithRandom(byte[] password, byte[] rnd64) {
        if (state != 0) throw new IllegalStateException("message already generated");
        // BoringSSL: x = sc_reduce(random); x <<= 3 (cofactor clearing).
        BigInteger priv = scReduce(rnd64).shiftLeft(3);
        privateKey = priv;

        byte[] pwdHash = sha512(password);
        passwordHash = pwdHash;
        BigInteger scalar = scReduce(pwdHash);
        // The BoringSSL "password scalar hack": add l / 2l / 4l so the low
        // three bits clear (compensates the omitted cofactor shift and keeps
        // the mask point in the prime-order subgroup).
        if (scalar.testBit(0)) scalar = scalar.add(L);
        if (scalar.testBit(1)) scalar = scalar.add(L.shiftLeft(1));
        if (scalar.testBit(2)) scalar = scalar.add(L.shiftLeft(2));
        if (!scalar.and(BigInteger.valueOf(7)).equals(BigInteger.ZERO)) {
            throw new IllegalStateException("password scalar adjustment failed");
        }
        passwordScalar = scalar;

        BigInteger[] base = scalarmultBase(priv);
        BigInteger[] mask = scalarmult(scalar,
            role == ROLE_ALICE ? new BigInteger[]{MX, MY} : new BigInteger[]{NX, NY});
        BigInteger[] pStar = add(base, mask);
        myMsg = encode(pStar);
        state = 1;
        return myMsg;
    }

    /** Process the peer's message and derive the shared 64-byte key material. */
    public byte[] processMsg(byte[] theirMsg, int maxOut) {
        if (state != 1) throw new IllegalStateException("generateMsg not called");
        if (theirMsg == null || theirMsg.length != MSG_SIZE) {
            throw new IllegalArgumentException("bad SPAKE2 message length");
        }
        BigInteger[] qStar = decode(theirMsg);
        // Unmask the peer's point with the OPPOSITE mask point (N for alice).
        BigInteger[] peersMask = scalarmult(passwordScalar,
            role == ROLE_ALICE ? new BigInteger[]{NX, NY} : new BigInteger[]{MX, MY});
        BigInteger[] q = sub(fromAffine(qStar), peersMask);
        BigInteger[] dh = scalarmult(privateKey, q);
        byte[] dhEnc = encode(dh);

        // Transcript: SHA-512 over length-prefixed (LE64) names, messages,
        // DH point and password hash — role-dependent ordering.
        MessageDigest md = sha512Digest();
        if (role == ROLE_ALICE) {
            updateLenPref(md, myName);
            updateLenPref(md, theirName);
            updateLenPref(md, myMsg);
            updateLenPref(md, theirMsg);
        } else {
            updateLenPref(md, theirName);
            updateLenPref(md, myName);
            updateLenPref(md, theirMsg);
            updateLenPref(md, myMsg);
        }
        updateLenPref(md, dhEnc);
        updateLenPref(md, passwordHash);
        byte[] key = md.digest();
        if (maxOut < key.length) {
            byte[] out = new byte[maxOut];
            System.arraycopy(key, 0, out, 0, maxOut);
            return out;
        }
        return key;
    }

    // ================= Ed25519 arithmetic (projective X:Y:Z:T) =================

    private static BigInteger[] identity() {
        return new BigInteger[]{BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
    }

    private static BigInteger[] fromAffine(BigInteger[] aff) {
        BigInteger x = aff[0].mod(P), y = aff[1].mod(P);
        return new BigInteger[]{x, y, BigInteger.ONE, x.multiply(y).mod(P)};
    }

    /** dbl-2008-hwcd */
    private static BigInteger[] dbl(BigInteger[] q) {
        BigInteger x1 = q[0], y1 = q[1], z1 = q[2];
        BigInteger a = x1.multiply(x1).mod(P);
        BigInteger b = y1.multiply(y1).mod(P);
        BigInteger c = z1.multiply(z1).mod(P).shiftLeft(1).mod(P);
        BigInteger d = P.subtract(a);
        BigInteger e = x1.add(y1).mod(P).pow(2).subtract(a).subtract(b).mod(P);
        BigInteger g = d.add(b).mod(P);
        BigInteger f = g.subtract(c).mod(P);
        BigInteger h = d.subtract(b).mod(P);
        return new BigInteger[]{
            e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P)};
    }

    /** add-2008-hwcd-3 (a = -1) */
    private static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
        BigInteger x1 = p[0], y1 = p[1], z1 = p[2], t1 = p[3];
        BigInteger x2 = q[0], y2 = q[1], z2 = q[2], t2 = q[3];
        BigInteger a = y1.subtract(x1).multiply(y2.subtract(x2)).mod(P);
        BigInteger b = y1.add(x1).multiply(y2.add(x2)).mod(P);
        BigInteger c = t1.multiply(TWO_D).multiply(t2).mod(P);
        BigInteger d = z1.shiftLeft(1).multiply(z2).mod(P);
        BigInteger e = b.subtract(a).mod(P);
        BigInteger f = d.subtract(c).mod(P);
        BigInteger g = d.add(c).mod(P);
        BigInteger h = b.add(a).mod(P);
        return new BigInteger[]{
            e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P)};
    }

    /** p - q via negation (twisted Edwards a=-1: neg = (-x, y, z, -t)). */
    private static BigInteger[] sub(BigInteger[] p, BigInteger[] q) {
        BigInteger x1 = p[0], y1 = p[1], z1 = p[2], t1 = p[3];
        BigInteger x2 = q[0].negate().mod(P), y2 = q[1], z2 = q[2], t2 = q[3].negate().mod(P);
        BigInteger a = y1.subtract(x1).multiply(y2.subtract(x2)).mod(P);
        BigInteger b = y1.add(x1).multiply(y2.add(x2)).mod(P);
        BigInteger c = t1.multiply(TWO_D).multiply(t2).mod(P);
        BigInteger d = z1.shiftLeft(1).multiply(z2).mod(P);
        BigInteger e = b.subtract(a).mod(P);
        BigInteger f = d.subtract(c).mod(P);
        BigInteger g = d.add(c).mod(P);
        BigInteger h = b.add(a).mod(P);
        return new BigInteger[]{
            e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P)};
    }

    /** Double-and-add scalar multiplication. */
    private static BigInteger[] scalarmult(BigInteger s, BigInteger[] point) {
        BigInteger[] q = point.length == 4 ? point : fromAffine(point);
        BigInteger[] r = identity();
        for (int i = s.bitLength() - 1; i >= 0; i--) {
            r = dbl(r);
            if (s.testBit(i)) r = add(r, q);
        }
        return r;
    }

    private static BigInteger[] scalarmultBase(BigInteger s) {
        return scalarmult(s, decode(BASE_POINT_ENC));
    }

    /** Ed25519 point encoding: y little-endian with the x sign bit on top. */
    private static byte[] encode(BigInteger[] pt) {
        BigInteger[] aff = toAffine(pt);
        byte[] out = new byte[32];
        byte[] yb = aff[1].toByteArray();
        for (int i = 0; i < yb.length && i < 32; i++) {
            out[i] = yb[yb.length - 1 - i];
        }
        if (aff[0].testBit(0)) out[31] |= (byte) 0x80;
        return out;
    }

    private static BigInteger[] toAffine(BigInteger[] pt) {
        BigInteger zInv = pt[2].modInverse(P);
        return new BigInteger[]{pt[0].multiply(zInv).mod(P), pt[1].multiply(zInv).mod(P)};
    }

    /** Decode with on-curve validation; throws IllegalArgumentException if invalid. */
    private static BigInteger[] decode(byte[] b) {
        if (b.length != 32) throw new IllegalArgumentException("bad point encoding length");
        BigInteger y = leBytes(b).and(BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE));
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        BigInteger x = u.multiply(v.modInverse(P)).mod(P)
            .modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).mod(P).multiply(v).mod(P).equals(u)) {
            x = x.multiply(SQRT_M1).mod(P);
            if (!x.multiply(x).mod(P).multiply(v).mod(P).equals(u)) {
                throw new IllegalArgumentException("point not on curve");
            }
        }
        if (((b[31] & 0x80) != 0) != x.testBit(0)) x = P.subtract(x);
        BigInteger x2 = x.multiply(x).mod(P);
        if (!y2.subtract(x2).mod(P)
            .equals(BigInteger.ONE.add(D.multiply(x2).multiply(y2)).mod(P))) {
            throw new IllegalArgumentException("point not on curve");
        }
        return new BigInteger[]{x, y};
    }

    // ================= helpers =================

    /** x25519_sc_reduce equivalent: 64-byte LE integer reduced mod the group order. */
    static BigInteger scReduce(byte[] b64) {
        return leBytes(b64).mod(L);
    }

    private static BigInteger leBytes(byte[] b) {
        byte[] rev = new byte[b.length];
        for (int i = 0; i < b.length; i++) rev[i] = b[b.length - 1 - i];
        return new BigInteger(1, rev);
    }

    private static BigInteger leHex(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return leBytes(b);
    }

    private static byte[] sha512(byte[] in) {
        return sha512Digest().digest(in);
    }

    private static MessageDigest sha512Digest() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void updateLenPref(MessageDigest md, byte[] data) {
        byte[] len = new byte[8];
        long l = data.length;
        for (int i = 0; i < 8; i++) {
            len[i] = (byte) (l & 0xff);
            l >>= 8;
        }
        md.update(len);
        md.update(data);
    }
}
