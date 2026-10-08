/*
 * TermDeb ADB bridge — Wireless Debugging transport manager.
 *
 * Owns the Android 11+ Wireless Debugging side of the transport registry:
 *  - pair(host, pairingPort, code): the REAL SPAKE2+/TLS pairing exchange
 *    (PairingConnection), persisting the returned device GUID.
 *  - connect(host, adbPort): TLS 1.3 client-cert socket + the unchanged
 *    RemoteDevice CNXN engine (adbd runs no A_AUTH inside the secure
 *    transport — the client certificate matched against the pairing record
 *    is the authentication; daemon/adb_wifi.cpp adbd_wifi_secure_connect).
 *  - status/reconnect: honest states for the Settings UI, exponential
 *    backoff (1s→30s, spec §18) for paired devices only.
 *
 * The pairing code is never stored or logged; only the GUID + endpoint are
 * persisted (WirelessDeviceStore).
 */
package com.termux.app.adb.wireless;

import android.content.Context;

import com.termux.app.adb.TermdebAdbBridge;
import com.termux.app.adb.remote.AdbKeyPair;
import com.termux.app.adb.remote.AdbKeyStore;
import com.termux.app.adb.remote.RemoteDevice;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLSocket;

/** Lifecycle owner of Wireless Debugging pairings and secure transports. */
public final class WirelessTransportManager {

    public static final String LOG_TAG = "WirelessTransportManager";

    /** Honest states for the Settings UI. */
    public enum State {
        NOT_PAIRED,
        PAIRED,          // credentials saved, not connected
        CONNECTING,
        CONNECTED,
        ERROR
    }

    private static final Object sLock = new Object();
    private static final Map<String, RemoteDevice> sDevices = new LinkedHashMap<>();
    private static volatile Context sAppContext;

    private static final int RECONNECT_MIN_MS = 1000;
    private static final int RECONNECT_MAX_MS = 30_000;
    private static volatile Thread sReconnectThread;

    private WirelessTransportManager() {
    }

    /** Initialize with the app context (called from TermdebAdbBridge.start). */
    public static void init(Context context) {
        sAppContext = context.getApplicationContext();
    }

    // ---------- pairing ----------

    /**
     * Pair with a device's Wireless Debugging pairing endpoint. Blocking;
     * call off the UI thread. Returns an honest user-facing verdict.
     */
    public static String pair(String host, int pairingPort, String pairingCode) {
        return pair(host, pairingPort, pairingCode, 0);
    }

    /**
     * Pair with an optional explicit Wireless Debugging ADB port (the
     * `adb connect` endpoint shown on the device's Wireless debugging
     * screen). The pairing protocol itself never reveals that port — its
     * socket is a different, short-lived endpoint — so when {@code adbPort}
     * is not given, the Settings Connect flow resolves it later from an
     * earlier successful connect or via mDNS (see connectToLastPaired).
     */
    public static String pair(String host, int pairingPort, String pairingCode,
                              int adbPort) {
        Context app = sAppContext;
        if (app == null) return "error: bridge not started";
        String code = pairingCode == null ? "" : pairingCode.trim();
        if (!code.matches("\\d{6}")) {
            return "error: pairing code must be exactly 6 digits";
        }
        final int knownAdbPort = (adbPort > 0 && adbPort <= 65535) ? adbPort : 0;
        try {
            AdbKeyPair adbPair = AdbKeyStore.get(app);
            String keyLine = adbPair.getAndroidPubkeyLine("termdeb");

            java.security.cert.X509Certificate cert = WirelessTls.identityCert(app);
            java.security.PrivateKey key = WirelessTls.identityKey(app);

            PairingConnection.Result result = PairingConnection.pair(host, pairingPort,
                code.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                () -> cert, () -> key, keyLine, 10_000);

            WirelessDeviceStore.get(app).put(result.deviceGuid, host, knownAdbPort,
                "paired");
            TermdebAdbBridge.logInfo(LOG_TAG, "paired with " + host + ":" + pairingPort
                + (knownAdbPort > 0 ? " (adb port " + knownAdbPort + ")" : "")
                + " [guid=" + result.deviceGuid + "]");
            return "Successfully paired to " + host + ":" + pairingPort
                + " [guid=" + result.deviceGuid + "]";
        } catch (PairingConnection.PairingException e) {
            TermdebAdbBridge.logWarn(LOG_TAG, "pairing with " + host + ":" + pairingPort
                + " failed: " + e.getMessage());
            return "Failed to pair to " + host + ":" + pairingPort + ": " + e.getMessage();
        } catch (Exception e) {
            TermdebAdbBridge.logWarn(LOG_TAG, "pairing with " + host + ":" + pairingPort
                + " failed: " + e.getClass().getSimpleName());
            return "Failed to pair to " + host + ":" + pairingPort
                + ": " + e.getClass().getSimpleName();
        }
    }

    /** Whether a device with this GUID is paired. */
    public static boolean isKnownGuid(String guid) {
        Context app = sAppContext;
        if (app == null) return false;
        for (WirelessDeviceStore.PairedDevice d : WirelessDeviceStore.get(app).all()) {
            if (d.guid.equals(guid)) return true;
        }
        return false;
    }

    /**
     * TLS connect ONLY when {@code host}:{@code port} matches a paired
     * device's stored Wireless Debugging endpoint (host always, and port
     * either matching the stored ADB port or the stored port being unknown).
     * This gives `adb connect host:port` the AOSP behavior of using the
     * secure transport for endpoints the pairing already introduced, while
     * every other endpoint keeps the plain legacy TCP attempt untouched.
     */
    public static String connectIfPaired(String host, int port) {
        Context app = sAppContext;
        if (app == null) return null;
        for (WirelessDeviceStore.PairedDevice d : WirelessDeviceStore.get(app).all()) {
            if (!d.host.equals(host)) continue;
            if (d.lastAdbPort > 0 && d.lastAdbPort != port) continue;
            return connect(host, port);
        }
        return null;
    }

    /**
     * Settings "Connect": reach the most recently paired device's Wireless
     * Debugging ADB port. The pairing handshake does not reveal that port
     * (the pairing socket is a separate, short-lived endpoint), so the port
     * is taken — in order — from an explicit {@code adbPort}, the last
     * successful connect stored with the pairing, or (when {@code adbPort}
     * is null) a bounded mDNS sweep of _adb-tls-connect._tcp. Passing
     * {@code adbPort == 0} skips the mDNS sweep (tests, plumbing). Every
     * outcome is an honest verdict; an unknown port is reported, never
     * guessed.
     */
    public static String connectToLastPaired(Context context, Integer adbPort) {
        Context app = context != null ? context.getApplicationContext() : sAppContext;
        if (app == null) return "error: bridge not started";
        java.util.List<WirelessDeviceStore.PairedDevice> all =
            WirelessDeviceStore.get(app).all();
        if (all.isEmpty()) {
            // No pairing records: if this very device's Wireless Debugging
            // was enabled via WRITE_SECURE_SETTINGS (no Wi-Fi needed), its
            // own adbd answers on loopback — connect to it directly.
            String local = WirelessDebuggingEnabler.deviceLoopbackEndpoint();
            if (local != null) {
                int colon = local.lastIndexOf(':');
                return connect("127.0.0.1",
                    Integer.parseInt(local.substring(colon + 1)));
            }
            return "error: no paired device";
        }
        WirelessDeviceStore.PairedDevice target = all.get(all.size() - 1);

        if (adbPort != null && adbPort > 0) {
            return connect(target.host, adbPort);
        }
        if (target.lastAdbPort > 0) {
            return connect(target.host, target.lastAdbPort);
        }
        if (adbPort != null) {
            return unknownAdbPortVerdict(target.host);
        }
        Integer resolved = WirelessDiscovery.findConnectPort(app, target.host);
        if (resolved == null) return unknownAdbPortVerdict(target.host);
        return connect(target.host, resolved);
    }

    private static String unknownAdbPortVerdict(String host) {
        return "cannot connect to " + host
            + ": Wireless Debugging ADB port unknown — check Wireless debugging on"
            + " the device, use Discover, or pair again with the ADB port filled in";
    }

    // ---------- secure connect ----------

    /**
     * Connect to a Wireless Debugging ADB port over TLS 1.3. Blocking; call
     * off the UI thread. Only succeeds when the real adbd completes the
     * secure CNXN handshake — no faked success (spec §23).
     */
    public static String connect(String host, int adbPort) {
        return connectInternal(host, adbPort, true);
    }

    /**
     * Until cleared by an explicit connect(), automatic reconnects stay
     * suppressed: a user-issued disconnect (CLI or Settings) must not be
     * silently undone by the reconnect loop during this app session. Boot
     * restores connectivity in a fresh process (statics reset).
     */
    private static volatile boolean sReconnectSuppressed;

    private static String connectInternal(String host, int adbPort, boolean manual) {
        Context app = sAppContext;
        if (app == null) return "error: bridge not started";
        if (manual) sReconnectSuppressed = false;
        final String spec = host + ":" + adbPort;

        synchronized (sLock) {
            RemoteDevice existing = sDevices.get(spec);
            if (existing != null && existing.isOnline()) {
                return "already connected to " + spec;
            }
            if (existing != null) {
                existing.close();
                sDevices.remove(spec);
            }
        }

        SSLSocket ssl = null;
        try {
            java.security.cert.X509Certificate cert = WirelessTls.identityCert(app);
            java.security.PrivateKey key = WirelessTls.identityKey(app);
            javax.net.ssl.SSLContext ctx = WirelessTls.newContext(cert, key);

            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(host, adbPort), 10_000);
            raw.setTcpNoDelay(true);
            ssl = WirelessTls.newClientSocket(ctx, raw, host, adbPort);
            ssl.startHandshake();
            final SSLSocket tlsSocket = ssl;

            RemoteDevice device = RemoteDevice.connectOverSocket(tlsSocket, spec,
                () -> AdbKeyStore.get(app), new RemoteDevice.Listener() {
                    @Override
                    public void onStateChanged(RemoteDevice d) {
                        if (d.getAuthState() == RemoteDevice.AuthState.OFFLINE) {
                            synchronized (sLock) {
                                if (sDevices.get(d.getSpec()) == d) {
                                    sDevices.remove(d.getSpec());
                                }
                            }
                        }
                        maybeKickReconnect();
                    }

                    @Override
                    public void onStreamClosed(RemoteDevice d, RemoteDevice.RemoteStream s) {
                    }
                }, 10_000);

            synchronized (sLock) {
                sDevices.put(spec, device);
            }
            // Remember the working ADB port for reconnect + display.
            WirelessDeviceStore store = WirelessDeviceStore.get(app);
            WirelessDeviceStore.PairedDevice paired = store.findByHost(host);
            if (paired != null) {
                store.put(paired.guid, host, adbPort, "connected");
            } else {
                store.put("unknown-guid-" + host.replace(':', '_'), host, adbPort, "connected");
            }
            TermdebAdbBridge.logInfo(LOG_TAG, "secure transport online: " + spec
                + " (" + device.getAuthState() + ")");
            return "connected to " + spec;
        } catch (IOException | RuntimeException
            | java.security.GeneralSecurityException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            TermdebAdbBridge.logWarn(LOG_TAG, "secure connect " + spec + " failed: " + reason);
            closeQuietly(ssl);
            return "cannot connect to " + spec
                + ": device does not speak Wireless Debugging (ADB TLS) protocol: " + reason;
        }
    }

    /** Drop one endpoint or every wireless transport. */
    public static String disconnect(String host, Integer port) {
        // Explicit user intent: no automatic reconnect until the next
        // explicit connect() (or a fresh process via boot()).
        sReconnectSuppressed = true;
        StringBuilder sb = new StringBuilder();
        synchronized (sLock) {
            if (host == null || host.trim().isEmpty()) {
                for (RemoteDevice d : new java.util.ArrayList<>(sDevices.values())) {
                    sb.append("disconnected ").append(d.getSpec()).append('\n');
                    d.close();
                    sDevices.remove(d.getSpec());
                }
                if (sb.length() == 0) sb.append("disconnected everything\n");
                return sb.toString();
            }
            String spec = host.trim() + ":" + (port != null ? port : 0);
            RemoteDevice d = sDevices.remove(spec);
            if (d != null) {
                d.close();
                return "disconnected " + spec + "\n";
            }
            return "error: no such device '" + spec + "'\n";
        }
    }

    /** Disconnect every wireless transport (Settings UI). */
    public static String disconnectAll() {
        return disconnect(null, null);
    }

    /** Full state reset between tests: transports, suppression, reconnect thread. */
    public static void clearStateForTest() {
        sReconnectSuppressed = true; // keep the stray reconnect thread out
        disconnect(null, null);
        Thread t = sReconnectThread;
        if (t != null) t.interrupt();
        synchronized (sLock) {
            sDevices.clear();
        }
    }

    /** Whether automatic reconnect is currently suppressed (test visibility). */
    static boolean reconnectSuppressedForTest() {
        return sReconnectSuppressed;
    }

    /** Snapshot of live wireless transports. */
    public static List<RemoteDevice> devices() {
        synchronized (sLock) {
            return new java.util.ArrayList<>(sDevices.values());
        }
    }

    /** Look up a live wireless transport by "host:port". */
    public static RemoteDevice bySpec(String spec) {
        synchronized (sLock) {
            return sDevices.get(spec);
        }
    }

    /** Whether any wireless transport is online. */
    public static boolean anyOnline() {
        synchronized (sLock) {
            for (RemoteDevice d : sDevices.values()) {
                if (d.isOnline()) return true;
            }
            return false;
        }
    }

    /** First online wireless transport, or null. */
    public static RemoteDevice anyDevice() {
        synchronized (sLock) {
            for (RemoteDevice d : sDevices.values()) {
                if (d.isOnline()) return d;
            }
            return null;
        }
    }

    /** Aggregate state for the Settings UI. */
    public static State statusState() {
        Context app = sAppContext;
        boolean paired = false;
        if (app != null && !WirelessDeviceStore.get(app).all().isEmpty()) {
            paired = true;
        }
        synchronized (sLock) {
            if (!sDevices.isEmpty()) {
                for (RemoteDevice d : sDevices.values()) {
                    if (d.isOnline()) return State.CONNECTED;
                }
                return State.CONNECTING;
            }
        }
        return paired ? State.PAIRED : State.NOT_PAIRED;
    }

    // ---------- reconnect ----------

    private static void maybeKickReconnect() {
        Context app = sAppContext;
        if (app == null) return;
        if (sReconnectSuppressed) return;
        synchronized (sLock) {
            if (sReconnectThread != null && sReconnectThread.isAlive()) return;
            sReconnectThread = new Thread(WirelessTransportManager::reconnectLoop,
                "adb-wireless-reconnect");
            sReconnectThread.setDaemon(true);
            sReconnectThread.start();
        }
    }

    /**
     * Exponential-backoff reconnect for paired devices with a known ADB
     * port. Never prompts for pairing codes again; when the pairing was
     * revoked the TLS/CNXN handshake simply keeps failing and the UI shows
     * the PAIRED (not connected) state honestly.
     */
    private static void reconnectLoop() {
        int delayMs = RECONNECT_MIN_MS;
        while (true) {
            Context app = sAppContext;
            if (app == null) return;
            if (sReconnectSuppressed) return;
            if (anyOnline()) return;
            boolean attempted = false;
            for (WirelessDeviceStore.PairedDevice d : WirelessDeviceStore.get(app).all()) {
                if (d.lastAdbPort <= 0) continue; // never connected; nothing to try
                attempted = true;
                String msg = connectInternal(d.host, d.lastAdbPort, false);
                if (msg.startsWith("connected") || msg.startsWith("already connected")) {
                    return;
                }
            }
            if (!attempted) return;
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                return;
            }
            delayMs = Math.min(delayMs * 2, RECONNECT_MAX_MS);
        }
    }

    /** Called from TermdebAdbBridge.start after the transport manager boots. */
    public static void boot() {
        Context app = sAppContext;
        if (app == null) return;
        boolean anyPairedWithPort = false;
        for (WirelessDeviceStore.PairedDevice d : WirelessDeviceStore.get(app).all()) {
            if (d.lastAdbPort > 0) {
                anyPairedWithPort = true;
                break;
            }
        }
        if (anyPairedWithPort) {
            maybeKickReconnect();
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
