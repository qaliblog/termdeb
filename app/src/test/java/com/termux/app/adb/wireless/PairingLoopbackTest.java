/*
 * TermDeb ADB bridge — Wireless Debugging pairing loopback tests (plain JVM).
 *
 * Runs the REAL protocol end-to-end over loopback TLS with no Robolectric:
 * the production PairingConnection (client) against FakePairingServer
 * (device pairing role), exercising TLS 1.3, the RFC 8446 keying-material
 * export under "adb-label\0", the SPAKE2 exchange, and the AES-128-GCM
 * PeerInfo exchange. No part of the protocol is mocked.
 */
package com.termux.app.adb.wireless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.conscrypt.Conscrypt;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Pairing protocol loopback (plain JVM — no Android, no Robolectric). */
public class PairingLoopbackTest {

    private static final String CODE = "123456";

    private FakePairingServer mServer;

    @Before
    public void setUp() throws Exception {
        // The uber Conscrypt JNI native exists for x86_64 (CI) but not for
        // aarch64 dev sandboxes; skip there rather than fail.
        assumeTrue("Conscrypt native unavailable on this platform",
            Conscrypt.isAvailable());
        mServer = new FakePairingServer();
    }

    @After
    public void tearDown() {
        if (mServer != null) mServer.stop();
    }

    @Test
    public void pairWithCorrectCodeCompletes() throws Exception {
        mServer.setPairingCode(CODE);

        PairingConnection.Result result = PairingConnection.pair(
            "127.0.0.1", mServer.port, CODE.getBytes("US-ASCII"),
            () -> mServer.serverCert, () -> mServer.serverKey,
            "BASE64KEY termdeb@termdeb\0", 10_000);

        assertNotNull(result);
        assertEquals("adb-T0F0B0G0-fake", result.deviceGuid);
        assertTrue("server must have decrypted the client's PeerInfo",
            mServer.awaitClientInfo(5000));
        assertEquals("client must present the pubkey line as its PeerInfo",
            "BASE64KEY termdeb@termdeb",
            new String(mServer.receivedKeyLine.get(), "US-ASCII"));
        assertNull("no failure on the device side, got: " + mServer.failureDetail(),
            mServer.failureDetail());
    }

    @Test
    public void pairWithWrongCodeFailsWithHonestMessage() throws Exception {
        mServer.setPairingCode("999999");

        try {
            PairingConnection.pair("127.0.0.1", mServer.port,
                CODE.getBytes("US-ASCII"),
                () -> mServer.serverCert, () -> mServer.serverKey,
                "BASE64KEY termdeb@termdeb\0", 10_000);
            fail("pairing with a wrong code must not succeed");
        } catch (PairingConnection.PairingException e) {
            assertTrue("failure must be the honest wrong-code verdict, got: "
                + e.getMessage(),
                e.getMessage().contains("wrong pairing code")
                    || e.getMessage().contains("invalid SPAKE2 message"));
            // No pairing code or key material may leak into the message.
            for (String banned : new String[]{"123456", "999999", "BASE64KEY"}) {
                assertTrue("message must not echo secrets",
                    !e.getMessage().contains(banned));
            }
            // The device side must report the SAME verdict — not some other
            // crash masquerading as the pairing failure.
            String detail = mServer.failureDetail();
            assertTrue("device must report the wrong-code verdict, got: " + detail,
                detail != null && detail.contains("wrong pairing code"));
        }
        assertEquals("no successful exchange recorded", 0, mServer.exchanges());
    }

    @Test
    public void pairFailsAgainstNonPairingEndpoint() throws Exception {
        mServer.stop();
        // A plain TCP listener that never completes the TLS handshake.
        java.net.ServerSocket plain = new java.net.ServerSocket(0,
            1, java.net.InetAddress.getLoopbackAddress());
        try {
            PairingConnection.PairingException e = null;
            try {
                PairingConnection.pair("127.0.0.1", plain.getLocalPort(),
                    CODE.getBytes("US-ASCII"),
                    () -> null, () -> null, "KEY\0", 2000);
                fail("pairing against a non-pairing endpoint must fail");
            } catch (PairingConnection.PairingException caught) {
                e = caught;
            }
            assertNotNull(e);
        } finally {
            plain.close();
        }
    }
}
