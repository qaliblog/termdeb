/*
 * TermDeb ADB bridge — Conscrypt TLS diagnostic (CI-only).
 *
 * This sandbox is aarch64 and org.conscrypt:conscrypt-openjdk-uber ships no
 * aarch64 native, so the TLS loopback tests can only execute on CI (x86_64).
 * When a loopback fails there with a bare "Broken pipe", this test isolates
 * whether the failure is in TLS/Conscrypt itself (handshake or exporter) or
 * in the pairing protocol on top — it runs the exact same primitives as
 * FakePairingServer/FakeSecureAdbd with none of the pairing protocol.
 */
package com.termux.app.adb.wireless;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.conscrypt.Conscrypt;

import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

public class ConscryptTlsDiagnosticTest {

    @Test
    public void handshakeAndExportOnBothEnds() throws Exception {
        // Skip where the Conscrypt JNI native is unavailable (e.g. aarch64
        // dev sandboxes); CI (x86_64) always exercises the real thing.
        assumeTrue("Conscrypt native unavailable on this platform",
            Conscrypt.isAvailable());

        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        X509Certificate cert = MiniCert.generate("termdeb-diag",
            (java.security.interfaces.RSAPublicKey) kp.getPublic(), kp.getPrivate());

        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<byte[]> serverExport = new AtomicReference<>();

        ServerSocket plain = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread server = new Thread(() -> {
            try (Socket raw = plain.accept()) {
                SSLContext ctx = context(cert, kp.getPrivate());
                SSLSocket ssl = (SSLSocket) ctx.getSocketFactory()
                    .createSocket(raw, raw.getInetAddress().getHostAddress(),
                        raw.getPort(), true);
                ssl.setUseClientMode(false);
                ssl.setEnabledProtocols(new String[]{"TLSv1.3"});
                ssl.startHandshake();
                serverExport.set(Conscrypt.exportKeyingMaterial(
                    ssl, WirelessTls.EXPORTED_KEY_LABEL, null,
                    WirelessTls.EXPORTED_KEY_SIZE));
                // Keep the socket open until the client finished its export.
                InputStream in = ssl.getInputStream();
                in.read(new byte[1]);
            } catch (Throwable t) {
                serverError.set(t);
            } finally {
                done.countDown();
            }
        }, "diag-server");
        server.setDaemon(true);
        server.start();

        SSLContext ctx = context(cert, kp.getPrivate());
        SSLSocket client = (SSLSocket) ctx.getSocketFactory().createSocket(
            "127.0.0.1", plain.getLocalPort());
        client.setUseClientMode(true);
        client.setEnabledProtocols(new String[]{"TLSv1.3"});
        client.startHandshake();
        byte[] clientExport = Conscrypt.exportKeyingMaterial(
            client, WirelessTls.EXPORTED_KEY_LABEL, null, WirelessTls.EXPORTED_KEY_SIZE);

        OutputStream out = client.getOutputStream();
        out.write(1);
        out.flush();
        client.close();

        assertTrue("server-side TLS failure: " + serverError.get(),
            done.await(10, TimeUnit.SECONDS) && serverError.get() == null);
        assertNotNull("client export must work", clientExport);
        assertTrue("client export must be 64 bytes", clientExport.length == 64);
        assertNotNull("server export must work", serverExport.get());
        assertTrue("server export must be 64 bytes", serverExport.get().length == 64);
        plain.close();
    }

    private SSLContext context(X509Certificate cert, java.security.PrivateKey key)
        throws Exception {
        X509ExtendedKeyManager km = new X509ExtendedKeyManager() {
            @Override public String chooseClientAlias(String[] keyType,
                java.security.Principal[] issuers, Socket socket) { return "diag"; }
            @Override public String chooseServerAlias(String keyType,
                java.security.Principal[] issuers, Socket socket) { return "diag"; }
            @Override public X509Certificate[] getCertificateChain(String alias) {
                return new X509Certificate[]{cert};
            }
            @Override public java.security.PrivateKey getPrivateKey(String alias) {
                return key;
            }
            @Override public String[] getClientAliases(String keyType,
                java.security.Principal[] issuers) { return new String[]{"diag"}; }
            @Override public String[] getServerAliases(String keyType,
                java.security.Principal[] issuers) { return new String[]{"diag"}; }
        };
        X509TrustManager tm = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS", Conscrypt.newProvider());
        ctx.init(new javax.net.ssl.KeyManager[]{km},
            new javax.net.ssl.TrustManager[]{tm}, new SecureRandom());
        return ctx;
    }
}
