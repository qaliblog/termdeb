package com.termux.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.R;
import com.termux.app.adb.TermdebAdbBridge;
import com.termux.app.adb.remote.AdbKeyStore;
import com.termux.app.adb.remote.AdbSettingsStore;
import com.termux.app.adb.remote.AdbTransportManager;
import com.termux.app.adb.remote.RemoteDevice;

/**
 * ADB &amp; Wireless Debugging section inside the existing TermDeb settings
 * screen (spec §7). Not a separate activity — a normal sub-screen fragment,
 * styled like every other preferences fragment in the app.
 *
 * It drives the same {@link AdbTransportManager} the guest-side `adb connect`
 * uses, so the status line reflects real transport state (spec §17: never
 * infer "Connected" from a process merely existing).
 */
@Keep
public class AdbPreferencesFragment extends PreferenceFragmentCompat {

    private static final String KEY_STATUS = "adb_status";
    private static final String KEY_DEVICE = "adb_device";
    private static final String KEY_ENABLED = "adb_enabled";
    private static final String KEY_AUTO_RECONNECT = "adb_auto_reconnect";
    private static final String KEY_CONNECT = "adb_connect";
    private static final String KEY_DISCONNECT = "adb_disconnect";
    private static final String KEY_REFRESH = "adb_refresh";
    private static final String KEY_PUBLIC_KEY = "adb_public_key";

    private static final String KEY_W_STATUS = "adb_wireless_status";
    private static final String KEY_W_PAIR = "adb_wireless_pair";
    private static final String KEY_W_CONNECT = "adb_wireless_connect";
    private static final String KEY_W_ENABLE_NO_WIFI = "adb_wireless_enable_no_wifi";
    private static final String KEY_W_DISCONNECT = "adb_wireless_disconnect";
    private static final String KEY_W_DISCOVER = "adb_wireless_discover";
    private static final String KEY_W_FORGET = "adb_wireless_forget";

    private final Handler mMain = new Handler(Looper.getMainLooper());

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(AdbPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.adb_preferences, rootKey);

        // Switches persist through AdbPreferencesDataStore (adb_enabled=false
        // also tears down live transports).
        configureActions(context);
        refreshStatus();
    }

    private void configureActions(@NonNull Context context) {
        Preference connect = findPreference(KEY_CONNECT);
        if (connect != null) {
            connect.setOnPreferenceClickListener(preference -> {
                runAsync(() -> {
                    String host = AdbTransportManager.savedEndpointHost();
                    if (host == null) {
                        return context.getString(R.string.adb_status_unconfigured);
                    }
                    // The manager reports honest results: "connected to ..." only
                    // after a real CNXN/AUTH handshake (spec §23).
                    return AdbTransportManager.connect(host, AdbTransportManager.savedEndpointPort());
                });
                return true;
            });
        }

        Preference disconnect = findPreference(KEY_DISCONNECT);
        if (disconnect != null) {
            disconnect.setOnPreferenceClickListener(preference -> {
                runAsync(AdbTransportManager::disconnectAll);
                return true;
            });
        }

        Preference refresh = findPreference(KEY_REFRESH);
        if (refresh != null) {
            refresh.setOnPreferenceClickListener(preference -> {
                refreshStatus();
                return true;
            });
        }

        Preference publicKey = findPreference(KEY_PUBLIC_KEY);
        if (publicKey != null) {
            publicKey.setOnPreferenceClickListener(preference -> {
                runAsync(() -> {
                    try {
                        return context.getString(R.string.adb_public_key_prefix) + " "
                            + AdbKeyStore.publicKeyLine(context);
                    } catch (Exception e) {
                        return context.getString(R.string.adb_error_key, e.getMessage());
                    }
                });
                return true;
            });
        }

        Preference wirelessPair = findPreference(KEY_W_PAIR);
        if (wirelessPair != null) {
            wirelessPair.setOnPreferenceClickListener(preference -> {
                showPairingDialog();
                return true;
            });
        }

        Preference wirelessEnableNoWifi = findPreference(KEY_W_ENABLE_NO_WIFI);
        if (wirelessEnableNoWifi != null) {
            wirelessEnableNoWifi.setOnPreferenceClickListener(preference -> {
                new AlertDialog.Builder(context)
                    .setTitle(R.string.adb_wireless_enable_no_wifi_title)
                    .setMessage(R.string.adb_wireless_enable_no_wifi_summary)
                    .setPositiveButton(android.R.string.ok, (d, w) ->
                        runAsync(() -> com.termux.app.adb.wireless.WirelessDebuggingEnabler
                            .enable(context)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return true;
            });
        }

        Preference wirelessConnect = findPreference(KEY_W_CONNECT);
        if (wirelessConnect != null) {
            wirelessConnect.setOnPreferenceClickListener(preference -> {
                runAsync(() -> com.termux.app.adb.wireless.WirelessTransportManager
                    .connectToLastPaired(context, null));
                return true;
            });
        }

        Preference wirelessDisconnect = findPreference(KEY_W_DISCONNECT);
        if (wirelessDisconnect != null) {
            wirelessDisconnect.setOnPreferenceClickListener(preference -> {
                runAsync(com.termux.app.adb.wireless.WirelessTransportManager::disconnectAll);
                return true;
            });
        }

        Preference wirelessDiscover = findPreference(KEY_W_DISCOVER);
        if (wirelessDiscover != null) {
            wirelessDiscover.setOnPreferenceClickListener(preference -> {
                runAsync(() -> {
                    java.util.List<com.termux.app.adb.wireless.WirelessDiscovery.DiscoveredService> found =
                        com.termux.app.adb.wireless.WirelessDiscovery.discover(context);
                    if (found.isEmpty()) {
                        return context.getString(R.string.adb_wireless_discover_none);
                    }
                    StringBuilder sb = new StringBuilder();
                    for (com.termux.app.adb.wireless.WirelessDiscovery.DiscoveredService s : found) {
                        sb.append(s.instanceName)
                            .append(" — ").append(s.host).append(':').append(s.port)
                            .append(s.isPairing() ? " (pairing)" : " (connect)")
                            .append('\n');
                    }
                    return sb.toString().trim();
                });
                return true;
            });
        }

        Preference wirelessForget = findPreference(KEY_W_FORGET);
        if (wirelessForget != null) {
            wirelessForget.setOnPreferenceClickListener(preference -> {
                com.termux.app.adb.wireless.WirelessDeviceStore.get(context).clear();
                refreshStatus();
                return true;
            });
        }
    }

    /** Most recently paired wireless device (for Connect + status). */
    @Nullable
    private static com.termux.app.adb.wireless.WirelessDeviceStore.PairedDevice lastPairedDevice(
        Context context) {
        java.util.List<com.termux.app.adb.wireless.WirelessDeviceStore.PairedDevice> all =
            com.termux.app.adb.wireless.WirelessDeviceStore.get(context).all();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    /**
     * Pairing dialog: IP, pairing port, 6-digit code, and an optional ADB
     * port (the Wireless debugging IPv4/port shown on the device — taking
     * it here saves a separate Connect step). The code lives only in the
     * dialog's EditText and is handed straight to the transport manager on
     * a worker thread; neither the dialog nor the manager logs it.
     */
    private void showPairingDialog() {
        final Context context = getContext();
        if (context == null) return;

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, 0);

        final EditText ipEdit = new EditText(context);
        ipEdit.setHint(R.string.adb_wireless_ip);
        ipEdit.setSingleLine(true);
        ipEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        container.addView(ipEdit);

        final EditText portEdit = new EditText(context);
        portEdit.setHint(R.string.adb_wireless_pairing_port);
        portEdit.setSingleLine(true);
        portEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        container.addView(portEdit);

        final EditText codeEdit = new EditText(context);
        codeEdit.setHint(R.string.adb_wireless_pairing_code);
        codeEdit.setSingleLine(true);
        codeEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        container.addView(codeEdit);

        final EditText adbPortEdit = new EditText(context);
        adbPortEdit.setHint(R.string.adb_wireless_adb_port);
        adbPortEdit.setSingleLine(true);
        adbPortEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        container.addView(adbPortEdit);

        new AlertDialog.Builder(context)
            .setTitle(R.string.adb_wireless_pair_title)
            .setView(container)
            .setPositiveButton(R.string.adb_wireless_pair_button, (dialog, which) -> {
                String host = ipEdit.getText().toString().trim();
                String portText = portEdit.getText().toString().trim();
                String code = codeEdit.getText().toString().trim();
                String adbPortText = adbPortEdit.getText().toString().trim();
                int port = -1;
                int adbPort = 0;
                try {
                    port = Integer.parseInt(portText);
                } catch (NumberFormatException ignored) {
                }
                try {
                    adbPort = Integer.parseInt(adbPortText);
                } catch (NumberFormatException ignored) {
                }
                final String fHost = host;
                final int fPort = port;
                final String fCode = code;
                final int fAdbPort = adbPort;
                runAsync(() -> com.termux.app.adb.wireless.WirelessTransportManager
                    .pair(fHost, fPort, fCode, fAdbPort));
            })
            .setNegativeButton(R.string.adb_wireless_cancel_button, null)
            .show();
    }

    /** Recompute the status/device lines from actual transport state. */
    private void refreshStatus() {
        Context context = getContext();
        if (context == null) return;

        AdbSettingsStore settings = AdbSettingsStore.get(context);
        AdbTransportManager.State state = AdbTransportManager.statusState();

        Preference status = findPreference(KEY_STATUS);
        if (status != null) {
            String summary;
            switch (state) {
                case CONNECTED:
                    summary = context.getString(R.string.adb_status_connected);
                    break;
                case CONNECTING:
                    summary = context.getString(R.string.adb_status_connecting);
                    break;
                case DISCONNECTED:
                    summary = context.getString(R.string.adb_status_disconnected);
                    break;
                case ERROR:
                    summary = context.getString(R.string.adb_status_error);
                    break;
                default:
                    summary = context.getString(R.string.adb_status_unconfigured);
                    break;
            }
            String host = settings.getLastHost();
            if (host != null && state != AdbTransportManager.State.CONNECTED) {
                summary += " (" + host + ":" + settings.getLastPort() + ")";
                summary += "\n" + context.getString(R.string.adb_status_saved_endpoint);
            }
            status.setSummary(summary);
        }

        Preference device = findPreference(KEY_DEVICE);
        if (device != null) {
            RemoteDevice online = AdbTransportManager.anyDevice();
            if (online == null) {
                online = com.termux.app.adb.wireless.WirelessTransportManager.anyDevice();
            }
            if (online != null) {
                device.setVisible(true);
                device.setSummary(online.getSpec()
                    + (online.getBanner() == null || online.getBanner().isEmpty()
                    ? "" : "\n" + online.getBanner()));
            } else {
                device.setVisible(false);
            }
        }

        // Wireless Debugging section: honest state — Paired ≠ Connected.
        Preference wirelessStatus = findPreference(KEY_W_STATUS);
        if (wirelessStatus != null) {
            com.termux.app.adb.wireless.WirelessTransportManager.State wState =
                com.termux.app.adb.wireless.WirelessTransportManager.statusState();
            String summary;
            switch (wState) {
                case CONNECTED:
                    summary = context.getString(R.string.adb_wireless_connected);
                    break;
                case CONNECTING:
                    summary = context.getString(R.string.adb_wireless_connecting);
                    break;
                case PAIRED:
                    summary = context.getString(R.string.adb_wireless_paired);
                    break;
                case ERROR:
                    summary = context.getString(R.string.adb_wireless_error);
                    break;
                default:
                    summary = context.getString(R.string.adb_wireless_not_paired);
                    break;
            }
            if (wState != com.termux.app.adb.wireless.WirelessTransportManager.State.NOT_PAIRED) {
                com.termux.app.adb.wireless.WirelessDeviceStore.PairedDevice last =
                    lastPairedDevice(context);
                if (last != null) {
                    summary += last.lastAdbPort > 0
                        ? "\n" + last.endpoint()
                        : "\n" + last.host + " — "
                            + context.getString(R.string.adb_wireless_port_unknown);
                }
            }
            wirelessStatus.setSummary(summary);
        }
    }

    /** Runs a blocking operation off the UI thread and shows the result in the status line. */
    private void runAsync(java.util.concurrent.Callable<String> work) {
        final Context context = getContext();
        if (context == null) return;

        final Preference status = findPreference(KEY_STATUS);
        mMain.post(() -> {
            if (status != null) status.setSummary(context.getString(R.string.adb_status_working));
        });

        new Thread(() -> {
            String result;
            try {
                result = work.call();
            } catch (Exception e) {
                result = context.getString(R.string.adb_error_generic, e.getMessage());
            }
            final String resultLine = result;
            mMain.post(() -> {
                Preference statusPref = findPreference(KEY_STATUS);
                if (statusPref != null && resultLine != null) {
                    statusPref.setSummary(resultLine);
                }
                refreshStatus();
            });
        }, "adb-settings-action").start();
    }

    /** Data store bridging the switches to {@link AdbSettingsStore}. */
    private static class AdbPreferencesDataStore extends PreferenceDataStore {

        private final Context mContext;
        private final AdbSettingsStore mSettings;

        private static AdbPreferencesDataStore sInstance;

        private AdbPreferencesDataStore(Context context) {
            mContext = context;
            mSettings = AdbSettingsStore.get(context);
        }

        public static synchronized AdbPreferencesDataStore getInstance(Context context) {
            if (sInstance == null) {
                sInstance = new AdbPreferencesDataStore(context);
            }
            return sInstance;
        }

        @Override
        public void putBoolean(String key, boolean value) {
            if (KEY_ENABLED.equals(key)) {
                mSettings.setEnabled(value);
                if (!value) {
                    AdbTransportManager.disconnectAll();
                }
            }
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            if (KEY_ENABLED.equals(key)) {
                return mSettings.isEnabled();
            }
            if (KEY_AUTO_RECONNECT.equals(key)) {
                return mSettings.isAutoReconnect();
            }
            return defValue;
        }
    }
}
