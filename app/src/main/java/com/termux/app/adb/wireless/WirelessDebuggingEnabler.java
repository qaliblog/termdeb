/*
 * TermDeb ADB bridge — privileged Wireless Debugging enabler.
 *
 * Some devices (TV boxes, many OEM ROMs) grey out Developer options →
 * "Wireless debugging" unless Wi-Fi is connected, even though the feature
 * itself (AdbService.isAdbWifiSupported) only requires the Wi-Fi system
 * feature. AOSP gates the transport on Settings.Global.ADB_WIFI_ENABLED
 * with the framework service toggling it via the MANAGE_DEBUGGING ADB
 * shell command; the "no Wi-Fi" rule is Settings-UI policy, not a
 * technical requirement, and adbd's Wireless Debugging TLS server runs
 * on any network interface regardless (its only connectivity checks live
 * in the Settings-UI and in AdbDebuggingManager's Wi-Fi-state listener,
 * which merely flips the setting back off when a Wi-Fi disconnect is
 * BROADCAST — an idle radio never broadcasts one).
 *
 * TermDeb already declares WRITE_SECURE_SETTINGS (granted through adb or
 * root tooling, standard practice for automation apps). With that grant
 * it can set ADB_WIFI_ENABLED directly and read the resulting TLS port
 * from the persist.adb.tls_server.enable / service.adb.tls.port system
 * properties — the same property AdbService sets before starting adbd.
 * A "shell" uid may also read those props, so the port can be recovered
 * even when the setting grant is missing.
 *
 * Nothing here fakes a network interface: mobile data stays untouched,
 * and when the grant is missing every call returns an honest verdict
 * instead of pretending success.
 */
package com.termux.app.adb.wireless;

import android.content.Context;
import android.os.SystemClock;

import com.termux.app.adb.TermdebAdbBridge;

/** WRITE_SECURE_SETTINGS-backed Wireless Debugging toggle (no Wi-Fi needed). */
public final class WirelessDebuggingEnabler {

    public static final String LOG_TAG = "WirelessDbgEnabler";

    /** Settings.Global key AdbService observes (AdbSettingsObserver). */
    public static final String SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled";
    /** AdbService.WIFI_PERSISTENT_CONFIG_PROPERTY — set before adbd starts. */
    public static final String PROP_TLS_ENABLE = "persist.adb.tls_server.enable";
    /** Populated by adbd once its TLS server is listening. */
    public static final String PROP_TLS_PORT = "service.adb.tls.port";

    /** adbd starts and opens its TLS server within seconds; stay bounded. */
    private static final long PORT_WAIT_MS = 3_000;
    private static final long PORT_POLL_MS = 250;

    private WirelessDebuggingEnabler() {
    }

    private static boolean putGlobalSettingViaResolver(String key, String value) {
        try {
            android.content.ContentResolver resolver = appResolver();
            if (resolver == null) return false;
            android.provider.Settings.Global.putString(resolver, key, value);
            return true;
        } catch (SecurityException e) {
            TermdebAdbBridge.logWarn(LOG_TAG,
                "WRITE_SECURE_SETTINGS grant missing: " + e.getMessage());
            return false;
        } catch (Throwable t) {
            TermdebAdbBridge.logWarn(LOG_TAG, "settings put failed: "
                + t.getClass().getSimpleName());
            return false;
        }
    }

    /** App context captured at TermdebAdbBridge.start (and settable in tests). */
    private static volatile Context sAppContext;

    /** Initialize with the app context (called from TermdebAdbBridge.start). */
    public static void init(Context context) {
        sAppContext = context.getApplicationContext();
    }

    private static android.content.ContentResolver appResolver() {
        Context app = sAppContext;
        return app != null ? app.getContentResolver() : null;
    }

    private static String getGlobalSetting(String key) {
        try {
            android.content.ContentResolver resolver = appResolver();
            if (resolver == null) return null;
            return android.provider.Settings.Global.getString(resolver, key);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Read a system property without shell access. Handles both the
     * public-able {@link android.os.SystemProperties} shape and the
     * "reflection on a hidden class is blocked" fallback (empty result).
     */
    private static String getProp(String name) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = cls.getMethod("get", String.class);
            Object v = get.invoke(null, name);
            return v != null ? v.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether the TLS server is (supposed to be) on. */
    public static boolean isEnabled() {
        if ("1".equals(getProp(PROP_TLS_ENABLE))) return true;
        return "1".equals(getGlobalSetting(SETTING_ADB_WIFI_ENABLED));
    }

    /** The adbd Wireless Debugging TLS port, 0 when not listening. */
    public static int currentTlsPort() {
        if (sTestTlsPort > 0) return sTestTlsPort;
        String port = getProp(PROP_TLS_PORT);
        if (port != null) {
            try {
                int p = Integer.parseInt(port.trim());
                if (p > 0 && p <= 65535) return p;
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }



    /** Enable Wireless Debugging without Wi-Fi; blocking, call off the UI thread. */
    public static String enable(Context context) {
        return enableInternal(context, PORT_WAIT_MS);
    }

    /** Disable Wireless Debugging; blocking, call off the UI thread. */
    public static String disable(Context context) {
        return disableInternal(context);
    }

    /** Test hook: {@code portWaitMs == 0} skips the TLS-port polling. */
    static String enableInternal(Context context, long portWaitMs) {
        Context app = context != null ? context.getApplicationContext() : sAppContext;
        if (app == null) return "error: bridge not started";
        if (android.os.Build.VERSION.SDK_INT < 30) {
            return "error: Wireless Debugging needs Android 11+ (this is "
                + android.os.Build.VERSION.SDK_INT + ")";
        }

        boolean granted = putGlobalSettingViaResolver(SETTING_ADB_WIFI_ENABLED, "1");
        if (!granted) {
            return "error: WRITE_SECURE_SETTINGS not granted — run from an adb shell:\n"
                + "  adb shell pm grant " + app.getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS\n"
                + "then retry Enable";
        }

        int port = awaitTlsPort(portWaitMs);
        if (port > 0) {
            TermdebAdbBridge.logInfo(LOG_TAG,
                "Wireless Debugging enabled without Wi-Fi, tls port " + port);
            return "Wireless Debugging enabled (no Wi-Fi needed) — adbd TLS port "
                + port;
        }
        // Setting went up but adbd has not reported a port yet. AdbService's
        // settings observer starts adbd at runtime too, so this usually
        // follows within moments; Connect picks the port up live.
        return "Wireless Debugging setting enabled (mobile data untouched) —"
            + " no adbd TLS port reported yet; try Connect in a moment, or"
            + " reboot the device once if it never appears";
    }

    static String disableInternal(Context context) {
        boolean granted = putGlobalSettingViaResolver(SETTING_ADB_WIFI_ENABLED, "0");
        if (!granted) {
            return "error: WRITE_SECURE_SETTINGS not granted — run from an adb shell:\n"
                + "  adb shell pm grant " + context.getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS";
        }
        return "Wireless Debugging disabled";
    }

    /** Test seam: pin the reported TLS port (0 = read the real property). */
    private static volatile int sTestTlsPort;

    static void setTestTlsPort(int port) {
        sTestTlsPort = port;
    }

    /** Poll for the adbd-reported TLS port, bounded by {@code waitMs}. */
    private static int awaitTlsPort(long waitMs) {
        long deadline = SystemClock.elapsedRealtime() + waitMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            int port = currentTlsPort();
            if (port > 0) return port;
            SystemClock.sleep(PORT_POLL_MS);
        }
        return currentTlsPort();
    }

    /**
     * The IP adbd's Wireless Debugging server answers on. Mobile-data
     * connections have no address a LAN peer could reach, so the Settings
     * Connect flow uses loopback (the same device); remote hosts need the
     * device's actual interface address, which is reported by Discover.
     */
    public static String deviceLoopbackEndpoint() {
        int port = currentTlsPort();
        return port > 0 ? "127.0.0.1:" + port : null;
    }
}
