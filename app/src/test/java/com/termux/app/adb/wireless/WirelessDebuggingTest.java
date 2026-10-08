/*
 * TermDeb ADB bridge — Android 11+ Wireless Debugging tests.
 *
 * The pairing tests run the REAL protocol end-to-end over loopback TLS:
 * the production PairingConnection (client) against FakePairingServer
 * (device), exercising TLS 1.3, the RFC 8446 keying-material export under
 * "adb-label\0", the SPAKE2 exchange, and the AES-128-GCM PeerInfo exchange.
 * No part of the protocol is mocked.
 */
package com.termux.app.adb.wireless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.security.cert.X509Certificate;

import com.termux.app.adb.remote.RemoteDevice;

/** Wireless Debugging pairing + secure transport integration tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class WirelessDebuggingTest {

    private static final String CODE = "123456";
    private android.content.Context mContext;
    private FakePairingServer mPairServer;
    private FakeSecureAdbd mAdbd;

    @Before
    public void setUp() throws Exception {
        mContext = org.robolectric.RuntimeEnvironment.getApplication();
        WirelessTransportManager.init(mContext);
        WirelessDebuggingEnabler.init(mContext);
        mPairServer = null;
        mAdbd = null;
    }

    @After
    public void tearDown() {
        // Full state reset: drops transports, suppresses the auto-reconnector
        // (an explicit disconnect must not be undone by a stray reconnect),
        // and interrupts any live reconnect thread between tests.
        WirelessTransportManager.clearStateForTest();
        if (mPairServer != null) mPairServer.stop();
        if (mAdbd != null) mAdbd.stop();
    }

    // ---------- pairing (real TLS + SPAKE2 over loopback) ----------

    @Test
    public void pairWithCorrectCodeCompletesAndStoresGuid() throws Exception {
        mPairServer = new FakePairingServer();
        mPairServer.setPairingCode(CODE);

        PairingConnection.Result result = PairingConnection.pair(
            "127.0.0.1", mPairServer.port, CODE.getBytes("US-ASCII"),
            () -> mPairServer.serverCert, () -> mPairServer.serverKey,
            "BASE64KEY termdeb@termdeb\0", 10_000);

        assertNotNull(result);
        assertEquals("adb-T0F0B0G0-fake", result.deviceGuid);
        assertTrue("server must have decrypted the client's PeerInfo",
            mPairServer.awaitClientInfo(5000));
        assertEquals("client must present the pubkey line as its PeerInfo",
            "BASE64KEY termdeb@termdeb",
            new String(mPairServer.receivedKeyLine.get(), "US-ASCII"));
        assertNull("no failure on the device side, got: " + mPairServer.failureDetail(),
            mPairServer.failureDetail());
    }

    @Test
    public void pairWithWrongCodeFailsWithHonestMessage() throws Exception {
        mPairServer = new FakePairingServer();
        mPairServer.setPairingCode("999999");

        try {
            PairingConnection.pair("127.0.0.1", mPairServer.port,
                CODE.getBytes("US-ASCII"),
                () -> mPairServer.serverCert, () -> mPairServer.serverKey,
                "BASE64KEY termdeb@termdeb\0", 10_000);
            fail("pairing with a wrong code must not succeed");
        } catch (PairingConnection.PairingException e) {
            assertTrue("failure must be the honest wrong-code verdict, got: "
                + e.getMessage(),
                e.getMessage().contains("wrong pairing code")
                    || e.getMessage().contains("invalid SPAKE2 message"));
            // No pairing code or key material may leak into the message.
            for (String banned : new String[]{"123456", "999999", "BASE64KEY"}) {
                assertTrue("message must not echo secrets", !e.getMessage().contains(banned));
            }
            // The device side must report the SAME verdict — not another crash.
            String detail = mPairServer.failureDetail();
            assertTrue("device must report the wrong-code verdict, got: " + detail,
                detail != null && detail.contains("wrong pairing code"));
        }
        assertEquals("no successful exchange recorded", 0, mPairServer.exchanges());
    }

    @Test
    public void pairFailsAgainstNonPairingEndpoint() throws Exception {
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

    // ---------- transport manager (Robolectric end-to-end) ----------

    @Test
    public void pairThenConnectThenDevicesThenForget_endToEnd() throws Exception {
        mPairServer = new FakePairingServer();
        mAdbd = new FakeSecureAdbd();

        // 1. Pair (real SPAKE2/TLS) — persisted with guid + host.
        String pairMsg = WirelessTransportManager.pair("127.0.0.1",
            mPairServer.port, CODE);
        assertTrue("pairing must succeed, got: " + pairMsg,
            pairMsg.startsWith("Successfully paired"));

        WirelessDeviceStore store = WirelessDeviceStore.get(mContext);
        assertEquals(1, store.all().size());
        WirelessDeviceStore.PairedDevice paired = store.all().get(0);
        assertEquals("127.0.0.1", paired.host);

        // 2. Connect: TLS 1.3 + no-AUTH CNXN against the fake secure adbd.
        String connectMsg = WirelessTransportManager.connect(
            "127.0.0.1", mAdbd.port);
        assertTrue("connect must succeed against the secure adbd, got: "
            + connectMsg + " | adbd-side: " + mAdbd.failureDetail(),
            connectMsg.startsWith("connected to"));

        WirelessDeviceStore.PairedDevice after = store.all().get(0);
        assertEquals("working ADB port must be remembered",
            mAdbd.port, after.lastAdbPort);

        // 3. The transport is registered and selectable by serial.
        RemoteDevice dev = WirelessTransportManager.bySpec(
            "127.0.0.1:" + mAdbd.port);
        assertNotNull(dev);
        assertTrue(dev.isOnline());
        assertEquals(1, WirelessTransportManager.devices().size());

        // 4. Disconnect drops it.
        String dmsg = WirelessTransportManager.disconnect("127.0.0.1", mAdbd.port);
        assertTrue(dmsg.contains("disconnected"));
        assertEquals(0, WirelessTransportManager.devices().size());
        assertNull(WirelessTransportManager.bySpec("127.0.0.1:" + mAdbd.port));

        // 5. Forget pairing clears the store entry.
        store.remove(after.guid);
        assertEquals(0, store.all().size());
    }

    @Test
    public void pairWithAdbPortThenSettingsConnectWorksEndToEnd() throws Exception {
        mPairServer = new FakePairingServer();
        mAdbd = new FakeSecureAdbd();

        // Settings flow: pair with the optional ADB port filled in, then
        // "Connect" (connectToLastPaired) with no explicit port.
        String pairMsg = WirelessTransportManager.pair("127.0.0.1",
            mPairServer.port, CODE, mAdbd.port);
        assertTrue("pairing must succeed, got: " + pairMsg,
            pairMsg.startsWith("Successfully paired"));

        String connectMsg = WirelessTransportManager.connectToLastPaired(
            mContext, null);
        assertTrue("Settings Connect must reach the device, got: " + connectMsg
            + " | adbd-side: " + mAdbd.failureDetail(),
            connectMsg.startsWith("connected to"));
        assertNotNull(WirelessTransportManager.bySpec(
            "127.0.0.1:" + mAdbd.port));
    }

    @Test
    public void connectToLastPairedWithoutPortReportsHonestFailure() throws Exception {
        mPairServer = new FakePairingServer();
        // Pair with no ADB port recorded (the realistic fresh-pairing state).
        assertTrue(WirelessTransportManager.pair("127.0.0.1",
            mPairServer.port, CODE).startsWith("Successfully paired"));
        // adbPort == 0 skips the mDNS sweep (none in JVM tests): the verdict
        // must honestly say the port is unknown, not silently do nothing.
        String msg = WirelessTransportManager.connectToLastPaired(mContext, 0);
        assertTrue("must report the unknown-port verdict, got: " + msg,
            msg.startsWith("cannot connect to"));
        assertTrue(msg.contains("port unknown"));
    }

    @Test
    public void connectToLastPairedWithoutAnyPairingFailsCleanly() {
        String msg = WirelessTransportManager.connectToLastPaired(mContext, null);
        assertEquals("error: no paired device", msg);
    }

    // ---------- privileged no-Wi-Fi enabler (WRITE_SECURE_SETTINGS) ----------

    @Test
    @Config(sdk = 30) // Wireless Debugging (adb_wifi_enabled) is Android 11+
    public void enablerStoresSettingAndReportsTlsPort() {
        // Robolectric's Settings provider accepts the write (the real
        // WRITE_SECURE_SETTINGS enforcement is Android-side); the TLS port
        // comes from the test seam standing in for service.adb.tls.port.
        WirelessDebuggingEnabler.setTestTlsPort(42567);
        try {
            String msg = WirelessDebuggingEnabler.enableInternal(mContext, 0);
            assertTrue("must enable, got: " + msg,
                msg.startsWith("Wireless Debugging enabled"));
            assertTrue(WirelessDebuggingEnabler.isEnabled());
            assertEquals("127.0.0.1:42567",
                WirelessDebuggingEnabler.deviceLoopbackEndpoint());
        } finally {
            WirelessDebuggingEnabler.setTestTlsPort(0);
        }
        WirelessDebuggingEnabler.disableInternal(mContext);
        assertTrue("disable must clear the setting",
            !WirelessDebuggingEnabler.isEnabled());
        assertNull(WirelessDebuggingEnabler.deviceLoopbackEndpoint());
    }

    @Test
    public void enablerRefusesBelowAndroid11() {
        String msg = WirelessDebuggingEnabler.enableInternal(mContext, 0);
        // Config(sdk = 28): the honest version verdict, not a pretend success.
        assertTrue(msg, msg.startsWith("error: Wireless Debugging needs Android 11+"));
    }

    @Test
    public void connectToLastPairedFallsBackToLocalAdbdWhenUnpaired() throws Exception {
        // No pairing records at all, but this very device's Wireless
        // Debugging was enabled without Wi-Fi: Connect must find its own
        // adbd TLS port on loopback.
        mAdbd = new FakeSecureAdbd();
        WirelessDebuggingEnabler.setTestTlsPort(mAdbd.port);
        try {
            String msg = WirelessTransportManager.connectToLastPaired(mContext, null);
            assertTrue("must connect to the local adbd, got: " + msg
                + " | adbd-side: " + mAdbd.failureDetail(),
                msg.startsWith("connected to"));
            assertNotNull(WirelessTransportManager.bySpec(
                "127.0.0.1:" + mAdbd.port));
        } finally {
            WirelessDebuggingEnabler.setTestTlsPort(0);
        }
    }

    @Test
    public void connectToUnknownHostReportsHonestFailure() throws Exception {
        String msg = WirelessTransportManager.connect("127.0.0.1", 1); // closed port
        assertTrue("must be an honest failure, got: " + msg,
            msg.startsWith("cannot connect to"));
        assertEquals("no transport may be registered on failure",
            0, WirelessTransportManager.devices().size());
    }

    @Test
    public void pairingCodeValidationIsEnforced() {
        assertEquals("error: pairing code must be exactly 6 digits",
            WirelessTransportManager.pair("127.0.0.1", 1, "12345"));
        assertEquals("error: pairing code must be exactly 6 digits",
            WirelessTransportManager.pair("127.0.0.1", 1, "1234567"));
        assertEquals("error: pairing code must be exactly 6 digits",
            WirelessTransportManager.pair("127.0.0.1", 1, "abcdef"));
        // Nothing persisted from the invalid attempts.
        assertEquals(0, WirelessDeviceStore.get(mContext).all().size());
    }

    @Test
    public void connectIfPairedRoutesWirelessEndpointsToTls() throws Exception {
        mAdbd = new FakeSecureAdbd();
        // Pre-seed the store as if this endpoint was paired earlier.
        WirelessDeviceStore.get(mContext).put("adb-guid-route", "127.0.0.1",
            mAdbd.port, null);

        String msg = WirelessTransportManager.connectIfPaired("127.0.0.1",
            mAdbd.port);
        assertNotNull("paired endpoint must take the TLS path", msg);
        assertTrue(msg + " | adbd-side: " + mAdbd.failureDetail(),
            msg.startsWith("connected to"));
    }

    @Test
    public void connectIfPairedIgnoresUnknownEndpoints() {
        assertNull("unpaired endpoint must not be hijacked into TLS",
            WirelessTransportManager.connectIfPaired("203.0.113.9", 5555));
    }

    @Test
    public void explicitDisconnectSuppressesAutoReconnect() throws Exception {
        mAdbd = new FakeSecureAdbd();
        WirelessDeviceStore.get(mContext).put("adb-guid-suppress", "127.0.0.1",
            mAdbd.port, null);

        assertTrue("initial connect must succeed, got: "
                + WirelessTransportManager.connect("127.0.0.1", mAdbd.port)
                + " | adbd-side: " + mAdbd.failureDetail(),
            WirelessTransportManager.bySpec("127.0.0.1:" + mAdbd.port) != null);

        // The user says disconnect: this must stick for the session.
        WirelessTransportManager.disconnect("127.0.0.1", mAdbd.port);
        assertTrue("explicit disconnect must suppress auto-reconnect",
            WirelessTransportManager.reconnectSuppressedForTest());

        // Even when the fake adbd stays reachable, no reconnect may spawn a
        // new transport for it within the backoff window (1 s first attempt).
        Thread.sleep(2500);
        assertNull("auto-reconnect must not undo an explicit disconnect",
            WirelessTransportManager.bySpec("127.0.0.1:" + mAdbd.port));

        // An explicit connect() clears the suppression again.
        assertTrue(WirelessTransportManager.connect("127.0.0.1", mAdbd.port)
                .startsWith("connected"));
        assertTrue("explicit connect must re-enable auto-reconnect",
            !WirelessTransportManager.reconnectSuppressedForTest());
    }

    @Test
    public void tlsIdentityDerivesFromAdbKeyAndPersists() throws Exception {
        X509Certificate cert = WirelessTls.identityCert(mContext);
        java.security.PrivateKey key = WirelessTls.identityKey(mContext);
        assertNotNull(cert);
        assertNotNull(key);

        // Same identity on second call (persisted, not regenerated).
        X509Certificate again = WirelessTls.identityCert(mContext);
        assertEquals(cert, again);

        // The cert's public key must be the ADB RSA public key. Derive via
        // the PKCS#8 DER (WirelessTls' own parser) so this works under any
        // JCA provider — getKeySpec(RSAPrivateCrtKeySpec.class) is not
        // provider-agnostic (JDK casts to RSAPrivateCrtKey).
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        java.math.BigInteger[] ne = WirelessTls.pkcs8RsaModulusExponent(
            key.getEncoded());
        java.security.interfaces.RSAPublicKey adbPub =
            (java.security.interfaces.RSAPublicKey) kf.generatePublic(
                new java.security.spec.RSAPublicKeySpec(ne[0], ne[1]));
        assertEquals("TLS identity must bind to the ADB RSA key",
            adbPub, cert.getPublicKey());
    }
}
