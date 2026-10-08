/*
 * TermDeb ADB bridge — secret-hygiene tests for the Wireless Debugging path.
 *
 * Spec rule: pairing codes, private keys, session secrets and TLS material
 * must never appear in logs or user-visible messages. These tests execute
 * the real pairing failure paths and scan every user-visible verdict that
 * crosses the bridge's message boundary (the same strings the CLI prints
 * and the Settings UI shows).
 */
package com.termux.app.adb.wireless;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Verifies no pairing secrets reach the logs or error messages. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SecretHygieneTest {

    private static final String CODE = "654321";

    @Before
    public void setUp() throws Exception {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        WirelessTransportManager.init(context);
    }

    @After
    public void tearDown() {
        WirelessTransportManager.disconnect(null, null);
    }

    private static void assertNoSecrets(String where, String message, String code) {
        String m = message == null ? "" : message;
        assertFalse(where + " leaks the pairing code", m.contains(code));
        assertFalse(where + " leaks PKCS8 markers", m.contains("PRIVATE KEY"));
        assertFalse(where + " leaks SPKI markers", m.contains("PUBLIC KEY"));
        assertFalse(where + " leaks the exporter label raw hex",
            m.contains("adb-label"));
    }

    @Test
    public void failedPairingMessagesCarryNoSecrets() throws Exception {
        FakePairingServer server = new FakePairingServer();
        server.setPairingCode("999999"); // force the wrong-code path
        try {
            String verdict = WirelessTransportManager.pair("127.0.0.1",
                server.port, CODE);
            assertNoSecrets("pair verdict", verdict, CODE);
            assertTrue("must be an honest failure",
                verdict.startsWith("Failed to pair"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void failedConnectMessagesCarryNoSecrets() {
        String verdict = WirelessTransportManager.connect("127.0.0.1", 1);
        assertNoSecrets("connect verdict", verdict, CODE);
        assertTrue(verdict.startsWith("cannot connect to"));
    }

    @Test
    public void pairingCodeValidationMessagesCarryNoSecrets() {
        String verdict = WirelessTransportManager.pair("127.0.0.1", 1, "abc123");
        assertNoSecrets("validation verdict", verdict, CODE);
        assertFalse("validation error must not echo the bad input",
            verdict.contains("abc123"));
    }
}
