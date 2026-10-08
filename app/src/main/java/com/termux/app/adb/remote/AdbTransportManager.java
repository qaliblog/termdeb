/*
 * TermDeb ADB bridge — network transport manager.
 *
 * Owns every live RemoteDevice, handles `adb connect/disconnect`, status
 * queries, and background reconnection with exponential backoff (1s → 30s).
 * The bridge's virtual device (emulator-5554) coexists with real network
 * transports, exactly like a workstation adb server hosts USB and network
 * devices side by side.
 */
package com.termux.app.adb.remote;

import android.content.Context;

import com.termux.app.adb.TermdebAdbBridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registry and lifecycle owner of all network ADB transports. */
public final class AdbTransportManager {

    private static final String LOG_TAG = "AdbTransportManager";

    /** States reported to the settings UI. */
    public enum State {
        UNCONFIGURED,        // no saved endpoint, nothing to connect to
        DISCONNECTED,        // saved endpoint exists, transport down
        CONNECTING,
        CONNECTED,
        UNAUTHORIZED,        // remote adbd wants key authorization (RSA dialog)
        WIRELESS_DEBUGGING_OFF, // endpoint refused (feature off / not a device)
        ERROR
    }

    private static final Object sLock = new Object();
    private static final Map<String, RemoteDevice> sDevices = new LinkedHashMap<>();
    private static volatile Context sAppContext;

    /** Exponential backoff bounds (spec §18). */
    private static final int RECONNECT_MIN_MS = 1000;
    private static final int RECONNECT_MAX_MS = 30_000;

    private static volatile Thread sReconnectThread;

    private AdbTransportManager() {
    }

    /** Initialize with the app context (called from TermdebAdbBridge.start). */
    public static void init(Context context) {
        sAppContext = context.getApplicationContext();
    }

    // ---------- connect / disconnect ----------

    /**
     * Connect to a remote adbd (`adb connect host:port`).
     *
     * @return the message the adb client prints ("connected to ..." or the
     * honest failure), exactly like AOSP's connect_service.
     */
    public static String connect(String host, int port) {
        if (host == null || host.trim().isEmpty()) {
            return "cannot connect to " + host + ": bad host";
        }
        final String h = host.trim();
        final String spec = h + ":" + port;

        AdbKeyPair keys;
        try {
            keys = AdbKeyStore.get(sAppContext);
        } catch (Exception e) {
            TermdebAdbBridge.logError(LOG_TAG, "Key load failed: " + e);
            return "cannot connect to " + spec + ": key error " + e.getMessage();
        }

        synchronized (sLock) {
            RemoteDevice existing = sDevices.get(spec);
            if (existing != null && existing.isOnline()) {
                return "already connected to " + spec;
            }
            // Offline leftovers are dropped; the new attempt replaces them.
            if (existing != null) {
                existing.close();
                sDevices.remove(spec);
            }
        }

        try {
            RemoteDevice.Listener listener = new RemoteDevice.Listener() {
                @Override
                public void onStateChanged(RemoteDevice device) {
                    if (device.getAuthState() == RemoteDevice.AuthState.OFFLINE) {
                        synchronized (sLock) {
                            if (sDevices.get(device.getSpec()) == device) {
                                sDevices.remove(device.getSpec());
                            }
                        }
                    }
                    maybeKickReconnect();
                }

                @Override
                public void onStreamClosed(RemoteDevice device, RemoteDevice.RemoteStream stream) {
                }
            };

            RemoteDevice device = RemoteDevice.connect(h, port,
                () -> AdbKeyStore.get(sAppContext), listener, 10_000);
            synchronized (sLock) {
                sDevices.put(spec, device);
            }
            AdbSettingsStore.get(sAppContext).setLastEndpoint(h, port);
            TermdebAdbBridge.logInfo(LOG_TAG, "connected to " + spec
                + " (" + device.getAuthState() + ")");
            // Authorizing a new device requires the user to accept the RSA
            // dialog on the remote screen; until then device services fail
            // with "unauthorized" — the state is surfaced honestly instead of
            // pretending a fully authorized transport.
            return "connected to " + spec;
        } catch (IOException e) {
            TermdebAdbBridge.logWarn(LOG_TAG, "connect " + spec + " failed: " + e.getMessage());
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("unauthorized device:")) {
                return msg;
            }
            if (msg != null && (msg.contains("handshake timeout")
                || msg.contains("connection closed during handshake"))) {
                return "cannot connect to " + spec
                    + ": device does not speak ADB protocol (wireless debugging off?)";
            }
            return "cannot connect to " + spec + ": " + msg;
        }
    }

    /** Disconnect one endpoint or everything (`adb disconnect [<addr>]). */
    public static String disconnect(String host, Integer port) {
        StringBuilder sb = new StringBuilder();
        synchronized (sLock) {
            if (host == null || host.trim().isEmpty()) {
                for (RemoteDevice d : new ArrayList<>(sDevices.values())) {
                    sb.append("disconnected ").append(d.getSpec()).append('\n');
                    d.close();
                    sDevices.remove(d.getSpec());
                }
                if (sb.length() == 0) {
                    sb.append("disconnected everything\n");
                }
                return sb.toString();
            }
            String spec = host.trim() + ":" + (port != null ? port : 5555);
            RemoteDevice d = sDevices.remove(spec);
            if (d != null) {
                d.close();
                return "disconnected " + spec + "\n";
            }
            return "error: no such device '" + spec + "'\n";
        }
    }

    /** Disconnect every network transport (used by the settings UI). */
    public static String disconnectAll() {
        return disconnect(null, null);
    }

    /** Snapshot of live network transports. */
    public static List<RemoteDevice> devices() {
        synchronized (sLock) {
            return new ArrayList<>(sDevices.values());
        }
    }

    /** Look up a live transport by spec ("host:port"). */
    public static RemoteDevice bySpec(String spec) {
        synchronized (sLock) {
            return sDevices.get(spec);
        }
    }

    /** Whether any network transport is currently online. */
    public static boolean anyOnline() {
        synchronized (sLock) {
            for (RemoteDevice d : sDevices.values()) {
                if (d.isOnline()) return true;
            }
            return false;
        }
    }

    /** Aggregate status for the Settings UI (worst-state wins). */
    public static State statusState() {
        synchronized (sLock) {
            if (sDevices.isEmpty()) {
                return sAppContext != null && AdbSettingsStore.get(sAppContext).getLastHost() != null
                    ? State.DISCONNECTED : State.UNCONFIGURED;
            }
            boolean anyConnecting = false;
            boolean anyUnauthorized = false;
            boolean anyOnline = false;
            for (RemoteDevice d : sDevices.values()) {
                switch (d.getAuthState()) {
                    case CONNECTED:
                        anyOnline = true;
                        break;
                    case CONNECTING:
                    case UNAUTHORIZED:
                        anyUnauthorized = true;
                        break;
                    case OFFLINE:
                        break;
                }
            }
            if (anyOnline) return State.CONNECTED;
            if (anyUnauthorized || anyConnecting) return State.CONNECTING;
            return State.DISCONNECTED;
        }
    }

    /** First online transport, or null. */
    public static RemoteDevice anyDevice() {
        synchronized (sLock) {
            for (RemoteDevice d : sDevices.values()) {
                if (d.isOnline()) return d;
            }
            return null;
        }
    }

    /** The saved endpoint (for the reconnect loop and UI), or null. */
    public static String savedEndpointHost() {
        return sAppContext == null ? null : AdbSettingsStore.get(sAppContext).getLastHost();
    }

    public static int savedEndpointPort() {
        return sAppContext == null ? 0 : AdbSettingsStore.get(sAppContext).getLastPort();
    }

    // ---------- reconnect loop ----------

    private static void maybeKickReconnect() {
        Context app = sAppContext;
        if (app == null) return;
        if (!AdbSettingsStore.get(app).isEnabled() || !AdbSettingsStore.get(app).isAutoReconnect()) {
            return;
        }
        if (savedEndpointHost() == null) return;
        synchronized (sLock) {
            if (sReconnectThread != null && sReconnectThread.isAlive()) return;
            sReconnectThread = new Thread(AdbTransportManager::reconnectLoop, "adb-reconnect");
            sReconnectThread.setDaemon(true);
            sReconnectThread.start();
        }
    }

    /** Exponential-backoff reconnect of the saved endpoint. */
    private static void reconnectLoop() {
        int delayMs = RECONNECT_MIN_MS;
        Context app = sAppContext;
        while (app != null) {
            AdbSettingsStore settings = AdbSettingsStore.get(app);
            if (!settings.isEnabled() || !settings.isAutoReconnect()
                || settings.getLastHost() == null) {
                return;
            }
            if (anyOnline()) return; // someone else connected; done
            String host = settings.getLastHost();
            int port = settings.getLastPort();
            String msg = connect(host, port);
            if (msg.startsWith("connected")) {
                return;
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                return;
            }
            delayMs = Math.min(delayMs * 2, RECONNECT_MAX_MS);
        }
    }

    /** Called from TermdebAdbBridge.start: load config, reconnect if enabled. */
    public static void boot() {
        Context app = sAppContext;
        if (app == null) return;
        AdbSettingsStore settings = AdbSettingsStore.get(app);
        if (settings.isEnabled() && settings.getLastHost() != null) {
            maybeKickReconnect();
        }
    }
}
