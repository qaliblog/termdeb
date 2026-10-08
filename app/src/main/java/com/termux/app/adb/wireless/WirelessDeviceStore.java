/*
 * TermDeb ADB bridge — persistence for paired Wireless Debugging devices.
 *
 * After a successful pairing the device replies with a GUID (adb_wifi.cpp
 * writes it to adb_known_hosts on a workstation). TermDeb persists the GUID
 * plus the last-seen Wireless Debugging address/port so the Settings UI and
 * the auto-reconnect logic can find the device again across app restarts.
 *
 * Deliberately NOT stored here (security rule §12/§13):
 *   - the pairing code (one-time use, never needed again)
 *   - private keys (AdbKeyStore owns the RSA key; the TLS cert derives from it)
 * This store is plain app-private SharedPreferences — the same protection
 * level AOSP's own adb client uses for its adb_known_hosts file.
 */
package com.termux.app.adb.wireless;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Paired-device registry backed by app-private SharedPreferences. */
public final class WirelessDeviceStore {

    private static final String PREFS = "termdeb_adb_wireless.xml";
    private static final String KEY_DEVICES = "paired_guids";
    private static final String PREFIX = "device.";

    /** One paired device. */
    public static final class PairedDevice {
        public final String guid;
        public final String host;
        public final int lastAdbPort;
        public final long pairedAt;
        public final long lastConnectedAt;
        public final String lastResult;

        PairedDevice(String guid, String host, int lastAdbPort, long pairedAt,
                     long lastConnectedAt, String lastResult) {
            this.guid = guid;
            this.host = host;
            this.lastAdbPort = lastAdbPort;
            this.pairedAt = pairedAt;
            this.lastConnectedAt = lastConnectedAt;
            this.lastResult = lastResult;
        }

        /** "host:port" convenience for display and reconnect. */
        public String endpoint() {
            return host + ":" + lastAdbPort;
        }
    }

    private final SharedPreferences mPrefs;

    private WirelessDeviceStore(Context context) {
        mPrefs = context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static WirelessDeviceStore get(Context context) {
        return new WirelessDeviceStore(context);
    }

    /** Insert or update the address info of a paired device. */
    public void put(String guid, String host, int lastAdbPort) {
        put(guid, host, lastAdbPort, null);
    }

    /**
     * Insert/update plus an optional connection-status note (e.g. the last
     * connect result) shown by the Settings UI.
     */
    public void put(String guid, String host, int lastAdbPort, String lastResult) {
        Set<String> guids = mPrefs.getStringSet(KEY_DEVICES, null);
        Set<String> updated = new java.util.LinkedHashSet<>();
        if (guids != null) updated.addAll(guids);
        updated.add(guid);
        long now = System.currentTimeMillis();
        PairedDevice existing = get(guid);
        SharedPreferences.Editor e = mPrefs.edit()
            .putStringSet(KEY_DEVICES, updated)
            .putString(PREFIX + guid + ".host", host)
            .putInt(PREFIX + guid + ".port", lastAdbPort)
            .putLong(PREFIX + guid + ".pairedAt",
                existing != null ? existing.pairedAt : now);
        if (lastResult != null) {
            e.putString(PREFIX + guid + ".result", lastResult);
            e.putLong(PREFIX + guid + ".lastConnectedAt", now);
        }
        e.apply();
    }

    public PairedDevice get(String guid) {
        String host = mPrefs.getString(PREFIX + guid + ".host", null);
        if (host == null) return null;
        return new PairedDevice(guid, host,
            mPrefs.getInt(PREFIX + guid + ".port", 0),
            mPrefs.getLong(PREFIX + guid + ".pairedAt", 0),
            mPrefs.getLong(PREFIX + guid + ".lastConnectedAt", 0),
            mPrefs.getString(PREFIX + guid + ".result", null));
    }

    /** Find a paired device by host (any port) — used for status display. */
    public PairedDevice findByHost(String host) {
        for (PairedDevice d : all()) {
            if (d.host.equals(host)) return d;
        }
        return null;
    }

    /** All paired devices, oldest pairing first. */
    public List<PairedDevice> all() {
        Set<String> guids = mPrefs.getStringSet(KEY_DEVICES, null);
        if (guids == null || guids.isEmpty()) return Collections.emptyList();
        List<PairedDevice> out = new ArrayList<>();
        List<String> sorted = new ArrayList<>(guids);
        Collections.sort(sorted);
        for (String guid : sorted) {
            PairedDevice d = get(guid);
            if (d != null) out.add(d);
        }
        return out;
    }

    /** Forget a device ("Forget pairing" — the device-side record stays until
     * the user revokes it there; pairing again replaces it). */
    public void remove(String guid) {
        Set<String> guids = mPrefs.getStringSet(KEY_DEVICES, null);
        if (guids == null || !guids.contains(guid)) return;
        Set<String> updated = new java.util.LinkedHashSet<>(guids);
        updated.remove(guid);
        SharedPreferences.Editor e = mPrefs.edit().putStringSet(KEY_DEVICES, updated);
        for (String suffix : new String[]{".host", ".port", ".pairedAt",
            ".lastConnectedAt", ".result"}) {
            e.remove(PREFIX + guid + suffix);
        }
        e.apply();
    }

    public void clear() {
        for (PairedDevice d : all()) {
            remove(d.guid);
        }
    }
}
