/*
 * TermDeb ADB bridge — Android 11+ Wireless Debugging pairing client.
 *
 * Implements the AOSP pairing protocol (packages/modules/adb/
 * pairing_connection/pairing_connection.cpp, Role::Client) over TLS 1.3:
 *
 *   1. TLS handshake presenting our ADB RSA key + self-signed certificate.
 *   2. Export 64 bytes of TLS keying material (label "adb-label\0").
 *   3. password = pairing_code_ascii ‖ exported_material; run SPAKE2
 *      (client = alice "adb pair client", device = bob "adb pair server").
 *   4. Exchange PairingPacket frames:
 *        header = { version=1 (u8), type (u8), payload_size (u32 BE) }
 *        SPAKE2_MSG (0) = 32-byte SPAKE2 message
 *        PEER_INFO  (1) = AES-128-GCM( full 8192-byte PeerInfo struct )
 *   5. PeerInfo sent by us: type=ADB_RSA_PUB_KEY(0),
 *      data = "<base64(android pubkey struct)> <user@host>\0" — the same
 *      line the legacy AUTH flow offers, so adbd authorizes this key.
 *   6. PeerInfo received: type=ADB_DEVICE_GUID(1), data = device GUID.
 *
 * Frame payload bounds follow pairing_connection.cpp: reject 0 or
 * > kMaxPeerInfoSize * 2 (16384). The decrypted peer info must be exactly
 * sizeof(PeerInfo) = 8192 bytes.
 *
 * The pairing code is passed in as bytes and never logged; on failure the
 * exception message contains no key material.
 */
package com.termux.app.adb.wireless;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

public final class PairingConnection {

    public static final int TYPE_SPAKE2_MSG = 0;
    public static final int TYPE_PEER_INFO = 1;

    public static final int PEER_INFO_TYPE_RSA_PUB_KEY = 0;
    public static final int PEER_INFO_TYPE_DEVICE_GUID = 1;

    public static final int PEER_INFO_SIZE = 8192;
    private static final int MAX_PAYLOAD = PEER_INFO_SIZE * 2;
    private static final int HEADER_SIZE = 6;
    private static final int CURRENT_VERSION = 1;

    /** Result of a completed pairing exchange. */
    public static final class Result {
        public final String deviceGuid;
        /** The device's PeerInfo type byte (for diagnostics only). */
        public final int peerInfoType;

        Result(String deviceGuid, int peerInfoType) {
            this.deviceGuid = deviceGuid;
            this.peerInfoType = peerInfoType;
        }
    }

    /** Pairing failed at some protocol/TLS stage; message is safe to show. */
    public static final class PairingException extends Exception {
        public PairingException(String message) {
            super(message);
        }

        public PairingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private PairingConnection() {
    }

    /**
     * Run the full pairing exchange against {@code host}:{@code pairingPort}.
     *
     * @param pairingCode exactly the 6 ASCII digits shown on the device
     * @param cert        our TLS identity certificate
     * @param key         the private key matching {@code cert}
     * @param ourKeyLine  the Android pubkey line ("base64 user@host\0") sent
     *                    inside our PeerInfo; adbd whitelists this key
     */
    public static Result pair(String host, int pairingPort, byte[] pairingCode,
                              X509CertificateHolder cert, PrivateKeyHolder key,
                              String ourKeyLine, int timeoutMs) throws PairingException {
        if (pairingCode == null || pairingCode.length == 0) {
            throw new PairingException("empty pairing code");
        }
        if (ourKeyLine == null || ourKeyLine.isEmpty()) {
            throw new PairingException("empty public key line");
        }
        Socket raw = null;
        SSLSocket ssl = null;
        try {
            SSLContext ctx = WirelessTls.newContext(cert.getCertificate(), key.getPrivateKey());
            raw = new Socket();
            raw.connect(new java.net.InetSocketAddress(host, pairingPort), timeoutMs);
            raw.setSoTimeout(timeoutMs);
            ssl = WirelessTls.newClientSocket(ctx, raw, host, pairingPort);
            ssl.startHandshake();
            return runExchange(ssl, pairingCode, ourKeyLine);
        } catch (PairingException e) {
            throw e;
        } catch (Exception e) {
            throw new PairingException("pairing failed: " + safeMessage(e), e);
        } finally {
            closeQuietly(ssl);
            closeQuietly(raw);
        }
    }

    /** Protocol state machine on an established TLS connection. */
    static Result runExchange(SSLSocket ssl, byte[] pairingCode, String ourKeyLine)
        throws PairingException, IOException {
        try {
            byte[] exported = WirelessTls.exportKeyingMaterial(
                ssl, WirelessTls.EXPORTED_KEY_LABEL, WirelessTls.EXPORTED_KEY_SIZE);
            if (exported == null || exported.length != WirelessTls.EXPORTED_KEY_SIZE) {
                throw new PairingException("TLS keying material export failed");
            }
            byte[] password = concat(pairingCode, exported);

            Spake2 spake = Spake2.newClient();
            byte[] ourMsg = spake.generateMsg(password);

            InputStream in = new DataInputStream(ssl.getInputStream());
            OutputStream out = ssl.getOutputStream();

            // ---- exchange SPAKE2 messages ----
            writeFrame(out, TYPE_SPAKE2_MSG, ourMsg);
            byte[] theirMsg = readFrame(in, TYPE_SPAKE2_MSG);
            if (theirMsg.length != Spake2.MSG_SIZE) {
                throw new PairingException("bad SPAKE2 message length from device");
            }
            PairingCipher cipher;
            byte[] keyMaterial;
            try {
                keyMaterial = spake.processMsg(theirMsg, Spake2.MAX_KEY_SIZE);
                cipher = new PairingCipher(keyMaterial);
            } catch (IllegalArgumentException | ArithmeticException e) {
                // Includes point-decoding failures (not on curve, or a
                // non-invertible coordinate) from hostile/garbage peers.
                throw new PairingException("device sent an invalid SPAKE2 message");
            }

            // ---- exchange PeerInfo (encrypted) ----
            byte[] peerInfo = new byte[PEER_INFO_SIZE];
            peerInfo[0] = (byte) PEER_INFO_TYPE_RSA_PUB_KEY;
            byte[] keyLine = ourKeyLine.getBytes(StandardCharsets.US_ASCII);
            if (keyLine.length > PEER_INFO_SIZE - 2) {
                throw new PairingException("public key line too long");
            }
            System.arraycopy(keyLine, 0, peerInfo, 1, keyLine.length);

            byte[] encrypted = cipher.encrypt(peerInfo);
            writeFrame(out, TYPE_PEER_INFO, encrypted);

            byte[] theirEncrypted = readFrame(in, TYPE_PEER_INFO);
            byte[] theirInfo;
            try {
                theirInfo = cipher.decrypt(theirEncrypted);
            } catch (PairingCipher.PairingCryptoException e) {
                // Tag mismatch: the pairing code was wrong (or the endpoint
                // is not who we think) — the honest user-facing message.
                throw new PairingException("wrong pairing code or connection dropped");
            }
            if (theirInfo.length != PEER_INFO_SIZE) {
                throw new PairingException("device sent a malformed peer info record");
            }
            int theirType = theirInfo[0] & 0xff;
            if (theirType != PEER_INFO_TYPE_DEVICE_GUID) {
                throw new PairingException("unexpected device response type " + theirType);
            }
            String guid = cString(theirInfo, 1);
            if (guid.isEmpty()) {
                throw new PairingException("device returned an empty GUID");
            }
            return new Result(guid, theirType);
        } catch (PairingCipher.PairingCryptoException e) {
            throw new PairingException("pairing crypto failure", e);
        }
    }

    // ---- frame codec (AOSP PairingPacketHeader) ----

    static void writeFrame(OutputStream out, int type, byte[] payload) throws IOException {
        if (payload.length == 0 || payload.length > MAX_PAYLOAD) {
            throw new IOException("invalid pairing payload size " + payload.length);
        }
        byte[] header = new byte[HEADER_SIZE];
        header[0] = (byte) CURRENT_VERSION;
        header[1] = (byte) type;
        header[2] = (byte) (payload.length >>> 24);
        header[3] = (byte) (payload.length >>> 16);
        header[4] = (byte) (payload.length >>> 8);
        header[5] = (byte) payload.length;
        out.write(header);
        out.write(payload);
        out.flush();
    }

    static byte[] readFrame(InputStream in, int expectedType) throws IOException, PairingException {
        byte[] header = new byte[HEADER_SIZE];
        readFully(in, header);
        int version = header[0] & 0xff;
        int type = header[1] & 0xff;
        if (version < 1 || version > CURRENT_VERSION) {
            throw new PairingException("unsupported pairing protocol version " + version);
        }
        if (type != expectedType) {
            throw new PairingException("unexpected pairing packet type " + type);
        }
        long len = ((header[2] & 0xffL) << 24) | ((header[3] & 0xffL) << 16)
            | ((header[4] & 0xffL) << 8) | (header[5] & 0xffL);
        if (len == 0 || len > MAX_PAYLOAD) {
            throw new PairingException("invalid pairing payload size " + len);
        }
        byte[] payload = new byte[(int) len];
        readFully(in, payload);
        return payload;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int got = 0;
        while (got < buf.length) {
            int n = in.read(buf, got, buf.length - got);
            if (n < 0) throw new IOException("EOF inside pairing frame");
            got += n;
        }
    }

    /** NUL-terminated string starting at {@code offset} inside {@code data}. */
    static String cString(byte[] data, int offset) {
        int end = offset;
        while (end < data.length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Never leaks key material: exception messages carry class + text only. */
    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        String s = (m == null || m.isEmpty()) ? t.getClass().getSimpleName()
            : t.getClass().getSimpleName() + ": " + m;
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Narrow holder so PairingConnection needs no android.* imports (JVM-testable). */
    public interface X509CertificateHolder {
        java.security.cert.X509Certificate getCertificate();
    }

    /** Narrow holder so PairingConnection needs no android.* imports (JVM-testable). */
    public interface PrivateKeyHolder {
        java.security.PrivateKey getPrivateKey();
    }
}
