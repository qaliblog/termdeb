/*
 * TermDeb ADB bridge test support — fake secure adbd (Wireless Debugging).
 *
 * The device side of the Android 11+ secure transport
 * (daemon/adb_wifi.cpp adbd_wifi_secure_connect): a TLS server that, after
 * the handshake, runs the UNCHANGED ADB CNXN protocol with NO A_AUTH token
 * exchange — the client certificate matched against the pairing record is
 * the authentication. Used to verify WirelessTransportManager's secure
 * connect path end-to-end.
 */
package com.termux.app.adb.wireless;

import org.conscrypt.Conscrypt;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

import com.termux.app.adb.remote.AdbPacket;

/** In-process fake adbd behind TLS for Wireless Debugging connect tests. */
public final class FakeSecureAdbd {

    public final int port;
    public final X509Certificate serverCert;
    public final PrivateKey serverKey;
    public final AtomicReference<String> openedService = new AtomicReference<>();
    /** Bytes the fake adbd sends as the shell/exec response. */
    public final AtomicReference<byte[]> reply = new AtomicReference<>(
        "uid=0(fake) gid=0(fake)\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    public final AtomicBoolean clientCertSeen = new AtomicBoolean(false);

    private volatile SSLServerSocket mServerSocket;
    private volatile Thread mThread;
    private volatile boolean mAcceptRunning = true;
    public volatile String failure;
    public volatile Throwable failureThrowable;

    public FakeSecureAdbd() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        serverCert = MiniCert.generate("termdeb-fake-adbd",
            (java.security.interfaces.RSAPublicKey) kp.getPublic(), kp.getPrivate());
        serverKey = kp.getPrivate();

        SSLServerSocket server = (SSLServerSocket) serverContext()
            .getServerSocketFactory().createServerSocket(0, 4,
                InetAddress.getLoopbackAddress());
        server.setEnabledProtocols(new String[]{"TLSv1.3"});
        port = server.getLocalPort();
        mServerSocket = server;
        mThread = new Thread(() -> acceptLoop(server), "fake-secure-adbd");
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
                ssl.setNeedClientAuth(true); // adbd requests the client cert
                serve(ssl);
            } catch (Throwable t) {
                if (mAcceptRunning) {
                    failureThrowable = t;
                    failure = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
                }
            }
        }
    }

    private void serve(SSLSocket ssl) {
        try (SSLSocket s = ssl) {
            s.startHandshake();
            Certificate[] peer = s.getSession().getPeerCertificates();
            clientCertSeen.set(peer != null && peer.length > 0);

            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            // Client CNXN -> server CNXN directly. NO A_AUTH (secure transport).
            AdbPacket cnxn = AdbPacket.readFrom(in);
            if (cnxn == null || cnxn.cmd != AdbPacket.A_CNXN) {
                failure = "expected CNXN, got "
                    + (cnxn == null ? "EOF" : String.format("0x%08x", cnxn.cmd));
                return;
            }
            AdbPacket.of(AdbPacket.A_CNXN, AdbPacket.CONNECT_VERSION,
                1 << 20, "device::ro.product.model=TermDebFake\0").writeTo(out);

            int adbdId = 0x4321;
            while (true) {
                AdbPacket p = AdbPacket.readFrom(in);
                if (p == null || p.cmd == AdbPacket.A_CLSE) break;
                if (p.cmd == AdbPacket.A_OPEN) {
                    openedService.set(new String(p.payload,
                        java.nio.charset.StandardCharsets.US_ASCII));
                    out.write(AdbPacket.of(AdbPacket.A_OKAY, adbdId, p.arg0, new byte[0])
                        .encode());
                    out.flush();
                    byte[] data = reply.get();
                    if (data != null && data.length > 0) {
                        out.write(AdbPacket.of(AdbPacket.A_WRTE, adbdId, p.arg0, data)
                            .encode());
                        out.flush();
                        // Lockstep: wait for the client's OKAY before continuing.
                        AdbPacket ack = AdbPacket.readFrom(in);
                        if (ack == null) break;
                    }
                } else if (p.cmd == AdbPacket.A_WRTE) {
                    out.write(AdbPacket.of(AdbPacket.A_OKAY, adbdId, p.arg0, new byte[0])
                        .encode());
                    out.flush();
                }
            }
        } catch (Throwable t) {
            failureThrowable = t;
            failure = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
        }
    }

    public void stop() {
        mAcceptRunning = false;
        SSLServerSocket server = mServerSocket;
        if (server != null && !server.isClosed()) {
            try {
                server.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Joins the accept thread and returns the device-side failure w/ stack. */
    public String failureDetail() {
        Throwable t2 = failureThrowable;
        if (t2 == null) return failure;
        StringBuilder sb = new StringBuilder(t2.toString());
        StackTraceElement[] frames = t2.getStackTrace();
        for (int i = 0; i < frames.length && i < 12; i++) {
            sb.append("\n\tat ").append(frames[i]);
        }
        return sb.toString();
    }
}
