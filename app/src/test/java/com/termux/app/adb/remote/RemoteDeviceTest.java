/*
 * TermDeb ADB bridge — fake adbd integration test.
 *
 * A minimal in-process adbd (TCP server socket) implementing the same
 * protocol surface RemoteDevice uses: CNXN/AUTH handshake, OPEN/OKAY/WRTE/CLSE
 * streams. Used to verify the network transport against AOSP-protocol
 * behavior without a physical device (spec §24).
 *
 * The fake adbd follows AOSP daemon semantics:
 *  - reply to the client CNXN with CNXN (or demand AUTH first),
 *  - accept an A_OPEN by answering A_OKAY (arg0 = remote id, arg1 = client id),
 *  - stream A_WRTE payloads, honoring the client's per-packet A_OKAYs,
 *  - terminate with A_CLSE.
 */
package com.termux.app.adb.remote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

/** Integration tests for RemoteDevice against an in-process fake adbd. */
public class RemoteDeviceTest {

    private FakeAdbd mFake;
    private AdbKeyPair mKeys;

    @Before
    public void setUp() throws Exception {
        mFake = new FakeAdbd();
        mFake.start();
        mKeys = AdbKeyPair.generate();
    }

    @After
    public void tearDown() {
        if (mFake != null) mFake.stop();
    }

    private RemoteDevice connectDevice(int timeoutMs) throws IOException {
        return RemoteDevice.connect("127.0.0.1", mFake.port,
            () -> mKeys, null, timeoutMs);
    }

    @Test
    public void testCnxnHandshakeAuthorizedImmediately() throws Exception {
        // adbd with no auth required: CNXN reply directly.
        mFake.onHandshake = (in, out) -> {
            AdbPacket cnxn = AdbPacket.readFrom(in);
            assertEquals(AdbPacket.A_CNXN, cnxn.cmd);
            assertEquals(AdbPacket.CONNECT_VERSION, cnxn.arg0);
            mFake.systemIdentity = "device::ro.product.model=TestDevice";
            AdbPacket.of(AdbPacket.A_CNXN, AdbPacket.CONNECT_VERSION,
                AdbPacket.MAX_PAYLOAD, mFake.systemIdentity).writeTo(out);
            return null;
        };

        try (RemoteDevice d = connectDevice(5000)) {
            assertEquals(RemoteDevice.AuthState.CONNECTED, d.getAuthState());
            assertTrue(d.isOnline());
            assertEquals("127.0.0.1:" + mFake.port, d.getSpec());
            assertEquals("banner must carry the device identity",
                "device::ro.product.model=TestDevice", d.getBanner());
            assertEquals("our maxdata must be clamped to the remote's",
                AdbPacket.MAX_PAYLOAD, d.getRemoteMaxPayload());
        }
    }

    @Test
    public void testRemoteMaxDataIsHonored() throws Exception {
        // An old/small-maxdata adbd: we must never send WRTEs larger than arg1.
        mFake.onHandshake = (in, out) -> {
            AdbPacket.readFrom(in);
            AdbPacket.of(AdbPacket.A_CNXN, AdbPacket.CONNECT_VERSION,
                4096, "device::").writeTo(out);
            return null;
        };
        mFake.onStream = (in, out, open) -> {
            // Adbd picks its own local id (AOSP local_socket opaque id); use a
            // distinct fixed value so cross-arg bugs cannot cancel out.
            final int adbdId = 0x1234;
            out.write(okay(adbdId, open.arg0).encode());
            out.flush();
            long total = 0;
            while (true) {
                AdbPacket p = AdbPacket.readFrom(in);
                if (p == null || p.cmd == AdbPacket.A_CLSE) break;
                if (p.cmd == AdbPacket.A_WRTE) {
                    assertTrue("WRTE must respect the remote's maxdata",
                        p.payload.length <= 4096);
                    total += p.payload.length;
                    // Lockstep-like: OKAY every write.
                    out.write(okay(adbdId, open.arg0).encode());
                    out.flush();
                }
            }
            assertEquals("all bytes must arrive", 10_000, total);
            return null;
        };

        try (RemoteDevice d = connectDevice(5000)) {
            assertEquals(4096, d.getRemoteMaxPayload());
            RemoteDevice.RemoteStream s = d.open("sync:SEND");
            assertTrue(s.awaitAccepted(5000));
            byte[] blob = new byte[10_000];
            for (int i = 0; i < blob.length; i++) blob[i] = (byte) (i * 13 + 3);
            s.write(blob, 0, blob.length);
            s.close();
        }
    }

    @Test
    public void testAuthTokenSigningFlow() throws Exception {
        mFake.onHandshake = (in, out) -> {
            AdbPacket.readFrom(in); // CNXN
            // adbd requires auth: challenge with a 20-byte TOKEN.
            byte[] token = new byte[20];
            new java.security.SecureRandom().nextBytes(token);
            AdbPacket.of(AdbPacket.A_AUTH, AdbPacket.ADB_AUTH_TOKEN, 0, token)
                .writeTo(out);
            // Client answers SIGNATURE; we "reject" it with a second TOKEN
            // (AOSP key-exhaustion path), which must trigger the public key.
            AdbPacket sig = AdbPacket.readFrom(in);
            assertEquals(AdbPacket.A_AUTH, sig.cmd);
            assertEquals(AdbPacket.ADB_AUTH_SIGNATURE, sig.arg0);
            assertEquals("RSA-2048 signature length", 256, sig.payload.length);
            byte[] token2 = new byte[20];
            new java.security.SecureRandom().nextBytes(token2);
            AdbPacket.of(AdbPacket.A_AUTH, AdbPacket.ADB_AUTH_TOKEN, 0, token2)
                .writeTo(out);
            AdbPacket sig2 = AdbPacket.readFrom(in);
            assertEquals(AdbPacket.A_AUTH, sig2.cmd);
            assertEquals(AdbPacket.ADB_AUTH_SIGNATURE, sig2.arg0);
            // User accepted the dialog: finalize with CNXN.
            mFake.systemIdentity = "device::";
            AdbPacket.of(AdbPacket.A_CNXN, AdbPacket.CONNECT_VERSION,
                AdbPacket.MAX_PAYLOAD, mFake.systemIdentity).writeTo(out);
            return null;
        };

        try (RemoteDevice d = connectDevice(5000)) {
            assertEquals(RemoteDevice.AuthState.CONNECTED, d.getAuthState());
        }
    }

    @Test
    public void testPublicKeyOfferedAfterSecondToken() throws Exception {
        mFake.onHandshake = (in, out) -> {
            AdbPacket.readFrom(in); // CNXN
            byte[] token = new byte[20];
            new java.security.SecureRandom().nextBytes(token);
            AdbPacket.of(AdbPacket.A_AUTH, AdbPacket.ADB_AUTH_TOKEN, 0, token)
                .writeTo(out);
            AdbPacket sig = AdbPacket.readFrom(in);
            assertEquals(AdbPacket.ADB_AUTH_SIGNATURE, sig.arg0);
            // Second challenge: the client must now offer its public key.
            byte[] token2 = new byte[20];
            new java.security.SecureRandom().nextBytes(token2);
            AdbPacket.of(AdbPacket.A_AUTH, AdbPacket.ADB_AUTH_TOKEN, 0, token2)
                .writeTo(out);
            AdbPacket sig2 = AdbPacket.readFrom(in);
            assertEquals(AdbPacket.ADB_AUTH_SIGNATURE, sig2.arg0);
            AdbPacket pub = AdbPacket.readFrom(in);
            assertEquals("client must offer RSAPUBLICKEY after key exhaustion",
                AdbPacket.A_AUTH, pub.cmd);
            assertEquals(AdbPacket.ADB_AUTH_RSAPUBLICKEY, pub.arg0);
            String pubLine = new String(pub.payload,
                java.nio.charset.StandardCharsets.UTF_8);
            // AOSP wire format: "<base64> <user@host>\0" — base64 FIRST.
            assertTrue("pubkey line must end with <user@host> and NUL: " + pubLine,
                pubLine.endsWith("termdeb\0"));
            int sp = pubLine.indexOf(' ');
            assertTrue(sp > 0);
            String b64 = pubLine.substring(0, sp);
            byte[] struct = java.util.Base64.getDecoder().decode(b64);
            assertEquals("Android pubkey struct is 524 bytes",
                AdbKeyPair.ANDROID_PUBKEY_ENCODED_SIZE, struct.length);
            assertEquals("payload must end with exactly one NUL",
                (byte) 0, pub.payload[pub.payload.length - 1]);
            assertEquals("last byte before NUL must be the userhost's final char, not a space",
                'b', pub.payload[pub.payload.length - 2]);
            // Never accept: leave the client unauthorized.
            return "hold";
        };

        try {
            connectDevice(900);
            fail("connect must not succeed without authorization");
        } catch (IOException e) {
            assertTrue("must report unauthorized, got: " + e.getMessage(),
                e.getMessage().contains("unauthorized"));
        }
    }

    @Test
    public void testUnauthorizedWhenDialogNeverAccepted() throws Exception {
        mFake.onHandshake = (in, out) -> {
            AdbPacket.readFrom(in); // CNXN
            // RSAPUBLICKEY request right away (device has no stored key).
            AdbPacket.of(AdbPacket.A_AUTH, AdbPacket.ADB_AUTH_RSAPUBLICKEY, 0,
                new byte[0]).writeTo(out);
            // Client sends the pubkey, then must wait; we never answer.
            AdbPacket pub = AdbPacket.readFrom(in);
            assertEquals(AdbPacket.A_AUTH, pub.cmd);
            assertEquals(AdbPacket.ADB_AUTH_RSAPUBLICKEY, pub.arg0);
            return "hold";
        };

        try {
            connectDevice(900);
            fail("connect must not succeed without authorization");
        } catch (IOException e) {
            assertTrue("must report unauthorized, got: " + e.getMessage(),
                e.getMessage().contains("unauthorized"));
        }
    }

    @Test
    public void testRefusedEndpointIsHonestFailure() throws Exception {
        int deadPort = findClosedPort();
        try {
            RemoteDevice.connect("127.0.0.1", deadPort, () -> mKeys, null, 1000);
            fail("connect to a dead endpoint must fail");
        } catch (IOException e) {
            assertTrue("failure must mention the endpoint: " + e.getMessage(),
                e.getMessage().contains("failed to connect to"));
        }
    }

    @Test
    public void testOldAdbdLockstepWrites() throws Exception {
        // Version 0x01000000 (pre-Dec-2017): checksums required, one OKAY per
        // WRTE. The client must pipeline exactly one packet and wait.
        mFake.onHandshake = (in, out) -> {
            AdbPacket.readFrom(in);
            AdbPacket.of(AdbPacket.A_CNXN, 0x01000000, 4096, "device::").writeTo(out);
            return null;
        };
        mFake.onStream = (in, out, open) -> {
            final int adbdId = 0x4444;
            out.write(okay(adbdId, open.arg0).encode());
            long total = 0;
            while (true) {
                AdbPacket p = AdbPacket.readFrom(in);
                if (p == null || p.cmd == AdbPacket.A_CLSE) break;
                if (p.cmd == AdbPacket.A_WRTE) {
                    assertTrue("old adbd needs valid checksums",
                        AdbPacket.checksum(p.payload, p.payload.length) != 0
                            || p.payload.length == 0);
                    total += p.payload.length;
                    // Lockstep: OKAY after each WRTE, never proactively.
                    out.write(okay(adbdId, open.arg0).encode());
                    out.flush();
                }
            }
            assertEquals(10_000, total);
            return null;
        };

        try (RemoteDevice d = connectDevice(5000)) {
            RemoteDevice.RemoteStream s = d.open("shell:cat");
            assertTrue(s.awaitAccepted(5000));
            byte[] blob = new byte[10_000];
            for (int i = 0; i < blob.length; i++) blob[i] = (byte) (i * 7 + 1);
            s.write(blob, 0, blob.length);
            s.close();
        }
    }

    @Test
    public void testShellStreamRoundtrip() throws Exception {
        mFake.onHandshake = (in, out) -> {
            AdbPacket.readFrom(in);
            AdbPacket.of(AdbPacket.A_CNXN, AdbPacket.CONNECT_VERSION,
                AdbPacket.MAX_PAYLOAD, "device::").writeTo(out);
            return null;
        };
        // Stream loop: accept one OPEN for "shell:echo hi", reply "hi\n", close.
        mFake.onStream = (in, out, open) -> {
            final int adbdId = 0x2222;
            out.write(okay(adbdId, open.arg0).encode());
            byte[] outBytes = "hi\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.write(wrte(adbdId, open.arg0, outBytes).encode());
            out.flush();
            AdbPacket ack = AdbPacket.readFrom(in);
            assertEquals("client must OKAY our write", AdbPacket.A_OKAY, ack.cmd);
            assertEquals("client's OKAY arg0 must be ITS local id", open.arg0, ack.arg0);
            assertEquals("client's OKAY arg1 must be OUR id", adbdId, ack.arg1);
            out.write(clse(adbdId, open.arg0).encode());
            out.flush();
            return null;
        };

        try (RemoteDevice d = connectDevice(5000)) {
            RemoteDevice.RemoteStream s = d.open("shell:echo hi");
            assertTrue(s.awaitAccepted(5000));
            byte[] buf = new byte[16];
            int n = s.read(buf);
            assertEquals("hi\n",
                new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
            // Remote closed: read must drain to -1.
            long deadline = System.currentTimeMillis() + 5000;
            int r = 0;
            while (System.currentTimeMillis() < deadline) {
                r = s.read(buf);
                if (r == -1) break;
            }
            assertEquals(-1, r);
        }
    }

    @Test
    public void testLargePayloadChunking() throws Exception {
        mFake.onHandshake = (in, out) -> {
            AdbPacket.readFrom(in);
            AdbPacket.of(AdbPacket.A_CNXN, AdbPacket.CONNECT_VERSION,
                AdbPacket.MAX_PAYLOAD, "device::").writeTo(out);
            return null;
        };
        final byte[] big = new byte[600_000]; // > MAX_PAYLOAD (256 KiB)
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31);
        }
        mFake.onStream = (in, out, open) -> {
            final int adbdId = 0x3333;
            out.write(okay(adbdId, open.arg0).encode());
            out.flush();
            long total = 0;
            long crc = 0;
            while (true) {
                AdbPacket p = AdbPacket.readFrom(in);
                if (p == null || p.cmd == AdbPacket.A_CLSE) break;
                if (p.cmd == AdbPacket.A_WRTE) {
                    for (byte b : p.payload) crc += b & 0xff;
                    total += p.payload.length;
                    out.write(okay(adbdId, open.arg0).encode());
                    out.flush();
                }
            }
            assertEquals("all bytes must arrive despite chunking", big.length, total);
            mFake.streamCrc.set(crc);
            return null;
        };

        try (RemoteDevice d = connectDevice(5000)) {
            RemoteDevice.RemoteStream s = d.open("sync:SEND");
            assertTrue(s.awaitAccepted(5000));
            s.write(big, 0, big.length);
            s.close();
            // The fake adbd thread finalizes its accounting asynchronously;
            // give it a moment to finish before asserting.
            assertTrue("fake adbd must finish serving",
                mFake.served.await(10, java.util.concurrent.TimeUnit.SECONDS));
            long expected = 0;
            for (byte b : big) expected += b & 0xff;
            assertEquals("payload bytes must arrive intact", expected,
                mFake.streamCrc.get().longValue());
        }
    }

    @Test
    public void testChecksumTolerance() throws Exception {
        // Modern adbd (>= 0x01000001) may send zeroed checksums; the client
        // must accept them. Script a WRTE with crc=0 but valid payload.
        mFake.onHandshake = (in, out) -> {
            AdbPacket.readFrom(in);
            AdbPacket.of(AdbPacket.A_CNXN, AdbPacket.CONNECT_VERSION,
                AdbPacket.MAX_PAYLOAD, "device::").writeTo(out);
            return null;
        };
        mFake.onStream = (in, out, open) -> {
            final int adbdId = 0x5555;
            out.write(okay(adbdId, open.arg0).encode());
            // Hand-craft a WRTE with a zero checksum (AOSP skip-checksum mode).
            byte[] payload = "zero-crc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] header = new byte[24];
            put32(header, 0, AdbPacket.A_WRTE);
            put32(header, 4, adbdId);
            put32(header, 8, open.arg0);
            put32(header, 12, payload.length);
            put32(header, 16, 0); // checksum deliberately zeroed
            put32(header, 20, AdbPacket.A_WRTE ^ 0xffffffff);
            out.write(header);
            out.write(payload);
            out.flush();
            AdbPacket ack = AdbPacket.readFrom(in);
            assertEquals(AdbPacket.A_OKAY, ack.cmd);
            out.write(clse(adbdId, open.arg0).encode());
            out.flush();
            return null;
        };

        try (RemoteDevice d = connectDevice(5000)) {
            RemoteDevice.RemoteStream s = d.open("shell:x");
            assertTrue(s.awaitAccepted(5000));
            byte[] buf = new byte[64];
            int n = s.read(buf);
            assertEquals("zero-crc",
                new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    // ---------- helpers ----------

    /**
     * AOSP packet-id convention: in OKAY/WRTE/CLSE sent by adbd, arg0 is the
     * ADDB's (sender's) local id and arg1 the CLIENT's id — the exact fields
     * the client matches its streams against.
     */
    private static AdbPacket okay(int adbdId, int clientId) {
        return AdbPacket.of(AdbPacket.A_OKAY, adbdId, clientId);
    }

    private static AdbPacket wrte(int adbdId, int clientId, byte[] payload) {
        return AdbPacket.of(AdbPacket.A_WRTE, adbdId, clientId, payload);
    }

    private static AdbPacket clse(int adbdId, int clientId) {
        return AdbPacket.of(AdbPacket.A_CLSE, adbdId, clientId);
    }

    private static void put32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static int findClosedPort() throws IOException {
        for (int i = 0; i < 50; i++) {
            ServerSocket probe = new ServerSocket(0);
            int p = probe.getLocalPort();
            probe.close();
            Socket s = new Socket();
            try {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", p), 200);
                s.close();
                continue; // something accepted?! try another port
            } catch (IOException expected) {
                return p;
            }
        }
        throw new IOException("no closed port found");
    }

    // ---------- fake adbd ----------

    /** Callbacks the individual tests install to script the fake adbd. */
    interface HandshakeStep {
        /** @return null to continue into the stream loop, "hold" to stop. */
        String step(InputStream in, OutputStream out) throws IOException;
    }

    interface StreamStep {
        String step(InputStream in, OutputStream out, AdbPacket open) throws IOException;
    }

    private static final class FakeAdbd {
        ServerSocket server;
        volatile int port;
        volatile String systemIdentity;
        final AtomicReference<Long> streamCrc = new AtomicReference<>(0L);
        volatile HandshakeStep onHandshake;
        volatile StreamStep onStream;
        volatile Thread worker;
        volatile boolean running;
        final java.util.concurrent.CountDownLatch served = new java.util.concurrent.CountDownLatch(1);

        void start() throws IOException {
            server = new ServerSocket(0);
            server.setSoTimeout(15_000);
            port = server.getLocalPort();
            running = true;
            Thread acceptor = new Thread(() -> {
                while (running) {
                    try {
                        Socket s = server.accept();
                        worker = new Thread(() -> serve(s), "fake-adbd");
                        worker.setDaemon(true);
                        worker.start();
                        break; // one connection per test
                    } catch (IOException e) {
                        if (running) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }, "fake-adbd-accept");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        void stop() {
            running = false;
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }

        private void serve(Socket s) {
            try {
                s.setTcpNoDelay(true);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                String r = onHandshake.step(in, out);
                if ("hold".equals(r)) {
                    // Hold the connection open silently (unauthorized case)
                    // until the client times out and disconnects.
                    long until = System.currentTimeMillis() + 15_000;
                    while (System.currentTimeMillis() < until && running) {
                        Thread.sleep(100);
                    }
                    return;
                }
                if (onStream != null) {
                    while (true) {
                        AdbPacket open = AdbPacket.readFrom(in);
                        if (open == null) return;
                        if (open.cmd != AdbPacket.A_OPEN) continue;
                        onStream.step(in, out, open);
                        return;
                    }
                }
            } catch (IOException | RuntimeException | InterruptedException e) {
                e.printStackTrace();
            } finally {
                served.countDown();
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
