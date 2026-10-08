/*
 * TermDeb ADB bridge test support — fake Wireless Debugging pairing endpoint.
 *
 * Implements the DEVICE side of the AOSP pairing protocol
 * (packages/modules/adb pairing_connection/pairing_server.cpp Role::Server)
 * over a real TLS 1.3 socket so the production PairingConnection is exercised
 * end-to-end: TLS handshake, keying-material export, SPAKE2 exchange, and the
 * AES-128-GCM PeerInfo exchange.
 *
 * Server behavior (pairing_server.cpp):
 *   1. accepts the TLS connection (server mode, any-cert trust),
 *   2. sends its SPAKE2 message first, then reads the client's,
 *   3. derives the shared key, decrypts the client's PeerInfo (which must
 *      decode cleanly — otherwise the pairing code was wrong),
 *   4. replies with its own PeerInfo: type ADB_DEVICE_GUID, data = GUID.
 */
package com.termux.app.adb.wireless;

import org.conscrypt.Conscrypt;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/** In-process fake adbd Wireless Debugging pairing server (TLS 1.3). */
public final class FakePairingServer {

    public final int port;
    public final X509Certificate serverCert;
    public final PrivateKey serverKey;
    public final String deviceGuid = "adb-T0F0B0G0-fake";

    public final AtomicReference<byte[]> receivedKeyLine = new AtomicReference<>();
    public final CountDownLatch clientInfoDecrypted = new CountDownLatch(1);
    private final CountDownLatch mDone = new CountDownLatch(1);

    private volatile Thread mThread;
    private volatile boolean mAcceptRunning = true;
    private volatile String mFailure;
    private final AtomicReference<Throwable> mFailureThrowable = new AtomicReference<>();
    private volatile byte[] mPasswordSnapshot;
    private volatile int mExchanges;

    public FakePairingServer() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        serverCert = MiniCert.generate("termdeb-fake-device",
            (java.security.interfaces.RSAPublicKey) kp.getPublic(), kp.getPrivate());
        serverKey = kp.getPrivate();

        SSLServerSocket server = (SSLServerSocket) serverContext()
            .getServerSocketFactory().createServerSocket(0, 4,
                InetAddress.getLoopbackAddress());
        server.setEnabledProtocols(new String[]{"TLSv1.3"});
        port = server.getLocalPort();

        mThread = new Thread(() -> acceptLoop(server), "fake-pairing-server");
        mThread.setDaemon(true);
        mThread.start();
    }

    private SSLContext serverContext() throws Exception {
        X509ExtendedKeyManager km = new X509ExtendedKeyManager() {
            @Override
            public String chooseClientAlias(String[] keyType,
                                            java.security.Principal[] issuers, Socket socket) {
                return "fake";
            }

            @Override
            public String chooseServerAlias(String keyType,
                                            java.security.Principal[] issuers, Socket socket) {
                return "fake";
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return new X509Certificate[]{serverCert};
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return serverKey;
            }

            @Override
            public String[] getClientAliases(String keyType,
                                             java.security.Principal[] issuers) {
                return new String[]{"fake"};
            }

            @Override
            public String[] getServerAliases(String keyType,
                                             java.security.Principal[] issuers) {
                return new String[]{"fake"};
            }
        };
        X509TrustManager tm = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS", Conscrypt.newProvider());
        ctx.init(new javax.net.ssl.KeyManager[]{km},
            new javax.net.ssl.TrustManager[]{tm}, new SecureRandom());
        return ctx;
    }

    private void acceptLoop(SSLServerSocket server) {
        while (mAcceptRunning) {
            try {
                SSLSocket ssl = (SSLSocket) server.accept();
                ssl.setUseClientMode(false);
                ssl.setEnabledProtocols(new String[]{"TLSv1.3"});
                serve(ssl);
            } catch (Throwable t) {
                if (mAcceptRunning) {
                    mFailureThrowable.set(t);
                    mFailure = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
                }
            }
        }
    }

    private void serve(SSLSocket ssl) {
        try (SSLSocket s = ssl) {
            s.startHandshake();
            byte[] exported = Conscrypt.exportKeyingMaterial(
                s, WirelessTls.EXPORTED_KEY_LABEL, null, WirelessTls.EXPORTED_KEY_SIZE);

            // The same 6-digit code the test hands to the client.
            byte[] password = concat(mCodeBytes, exported);
            mPasswordSnapshot = password;

            Spake2 spake = Spake2.newServer();
            byte[] serverMsg = spake.generateMsg(password);

            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            // Server speaks first (pairing_server.cpp Role::Server).
            writeFrame(out, PairingConnection.TYPE_SPAKE2_MSG, serverMsg);
            byte[] clientMsg = readFrame(in, PairingConnection.TYPE_SPAKE2_MSG);
            byte[] keyMaterial = spake.processMsg(clientMsg, Spake2.MAX_KEY_SIZE);
            PairingCipher cipher = new PairingCipher(keyMaterial);

            // Client's encrypted PeerInfo: type + "base64 name@host\0".
            byte[] encryptedInfo = readFrame(in, PairingConnection.TYPE_PEER_INFO);
            byte[] clientInfo;
            try {
                clientInfo = cipher.decrypt(encryptedInfo);
            } catch (PairingCipher.PairingCryptoException e) {
                // Wrong pairing code — the honest protocol failure. Reply with
                // an undecryptable PeerInfo frame so the CLIENT fails fast
                // with the same verdict instead of waiting on its read
                // timeout (AOSP just closes; same result, deterministic).
                mFailure = "wrong pairing code (tag mismatch)";
                try {
                    PairingCipher bogus = new PairingCipher(new byte[64]);
                    byte[] info = new byte[8192];
                    info[0] = (byte) PairingConnection.PEER_INFO_TYPE_DEVICE_GUID;
                    writeFrame(out, PairingConnection.TYPE_PEER_INFO,
                        bogus.encrypt(info));
                } catch (Exception ignored) {
                }
                return;
            }
            int type = clientInfo[0] & 0xff;
            if (type != PairingConnection.PEER_INFO_TYPE_RSA_PUB_KEY) {
                mFailure = "unexpected client info type " + type;
                return;
            }
            receivedKeyLine.set(PairingConnection.cString(clientInfo, 1)
                .getBytes(StandardCharsets.US_ASCII));
            mExchanges++;
            clientInfoDecrypted.countDown();

            // Device GUID reply.
            byte[] guidInfo = new byte[8192];
            guidInfo[0] = (byte) PairingConnection.PEER_INFO_TYPE_DEVICE_GUID;
            byte[] guid = deviceGuid.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(guid, 0, guidInfo, 1, guid.length);
            writeFrame(out, PairingConnection.TYPE_PEER_INFO, cipher.encrypt(guidInfo));
        } catch (Throwable t) {
            mFailureThrowable.set(t);
            mFailure = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
        } finally {
            mDone.countDown();
        }
    }

    private volatile byte[] mCodeBytes = "123456".getBytes(StandardCharsets.US_ASCII);

    /** The pairing code this fake device expects. */
    public void setPairingCode(String code) {
        mCodeBytes = code.getBytes(StandardCharsets.US_ASCII);
    }

    public String failure() {
        return mFailure;
    }

    /**
     * The device-side failure including its stack — so test failures show WHY
     * the fake server died instead of only the client's broken-pipe symptom.
     * serve() records its exception synchronously before the client's next
     * write can fail, so no waiting is needed once the client has failed.
     */
    public String failureDetail() {
        Throwable t = mFailureThrowable.get();
        if (t == null) return mFailure; // protocol verdicts stay message-only
        StringBuilder sb = new StringBuilder(t.toString());
        StackTraceElement[] frames = t.getStackTrace();
        for (int i = 0; i < frames.length && i < 12; i++) {
            sb.append("\n\tat ").append(frames[i]);
        }
        return sb.toString();
    }

    public int exchanges() {
        return mExchanges;
    }

    public boolean awaitClientInfo(long ms) throws InterruptedException {
        return clientInfoDecrypted.await(ms, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        mAcceptRunning = false;
        if (mThread != null) mThread.interrupt();
    }

    // ---- frame codec shared with PairingConnection ----

    static void writeFrame(OutputStream out, int type, byte[] payload) throws Exception {
        byte[] header = new byte[6];
        header[0] = 1;
        header[1] = (byte) type;
        header[2] = (byte) (payload.length >>> 24);
        header[3] = (byte) (payload.length >>> 16);
        header[4] = (byte) (payload.length >>> 8);
        header[5] = (byte) payload.length;
        out.write(header);
        out.write(payload);
        out.flush();
    }

    static byte[] readFrame(InputStream in, int expectedType) throws Exception {
        byte[] header = new byte[6];
        int got = 0;
        while (got < 6) {
            int n = in.read(header, got, 6 - got);
            if (n < 0) throw new java.io.EOFException("EOF in pairing header");
            got += n;
        }
        int type = header[1] & 0xff;
        if (type != expectedType) throw new IllegalStateException("bad frame type " + type);
        long len = ((header[2] & 0xffL) << 24) | ((header[3] & 0xffL) << 16)
            | ((header[4] & 0xffL) << 8) | (header[5] & 0xffL);
        byte[] payload = new byte[(int) len];
        got = 0;
        while (got < payload.length) {
            int n = in.read(payload, got, payload.length - got);
            if (n < 0) throw new java.io.EOFException("EOF in pairing payload");
            got += n;
        }
        return payload;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
