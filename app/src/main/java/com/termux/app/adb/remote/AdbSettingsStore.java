/*
 * TermDeb ADB bridge — settings persistence for network ADB transports.
 *
 * Stores ONLY non-sensitive configuration (the private key lives in
 * AdbKeyStore, never here): enabled, auto-reconnect, and the saved endpoint.
 * Mirrors the AdbSettings model from the requirements while staying in the
 * project's Java conventions.
 */
package com.termux.app.adb.remote;

import android.content.Context;
import android.content.SharedPreferences;

/** Non-sensitive ADB connection settings (SharedPreferences-backed). */
public final class AdbSettingsStore {

    private static final String PREFS = "termdeb_adb_settings.xml";

    /** SharedPreferences keys. */
    private static final String KEY_ENABLED = "adb_network_enabled";
    private static final String KEY_AUTO_RECONNECT = "adb_auto_reconnect";
    private static final String KEY_LAST_HOST = "adb_last_host";
    private static final String KEY_LAST_PORT = "adb_last_port";

    private final SharedPreferences mPrefs;

    private AdbSettingsStore(Context context) {
        mPrefs = context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static AdbSettingsStore get(Context context) {
        return new AdbSettingsStore(context);
    }

    public boolean isEnabled() {
        return mPrefs.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public boolean isAutoReconnect() {
        return mPrefs.getBoolean(KEY_AUTO_RECONNECT, true);
    }

    public void setAutoReconnect(boolean auto) {
        mPrefs.edit().putBoolean(KEY_AUTO_RECONNECT, auto).apply();
    }

    public String getLastHost() {
        return mPrefs.getString(KEY_LAST_HOST, null);
    }

    public int getLastPort() {
        return mPrefs.getInt(KEY_LAST_PORT, 0);
    }

    public void setLastEndpoint(String host, int port) {
        mPrefs.edit().putString(KEY_LAST_HOST, host).putInt(KEY_LAST_PORT, port).apply();
    }

    public void clearLastEndpoint() {
        mPrefs.edit().remove(KEY_LAST_HOST).remove(KEY_LAST_PORT).apply();
    }
}
