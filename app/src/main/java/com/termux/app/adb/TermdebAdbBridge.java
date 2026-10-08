package com.termux.app.adb;

import android.content.Context;
import android.os.Environment;

import com.termux.app.adb.remote.AdbTransportManager;
import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.IOException;

/**
 * App-lifecycle glue for the TermDeb transparent ADB bridge.
 *
 * The bridge is a single app-owned server listening on 127.0.0.1:5037 that
 * speaks Google's ADB smart protocol. The Debian/PRoot guest reaches it over
 * the shared loopback (proot does not namespace the network) with the bundled
 * /usr/bin/adb client, so programs inside the guest use the normal
 * "adb devices / shell / push / install / ..." interface without knowing they
 * run inside TermDeb.
 *
 * Lifecycle: started from {@link com.termux.app.TermuxApplication#onCreate()},
 * stopped when the app process dies (guest clients then see standard
 * connection-refused behavior, like when a real adb server is not running).
 *
 * Security: loopback bind only, no authentication (same sandbox trust boundary
 * as adbd on a device for its own localhost), no elevation of any kind — every
 * device operation is performed with the app's own UID and Android rejects
 * what it rejects, honestly.
 */
public final class TermdebAdbBridge {

    public static final String LOG_TAG = "TermdebAdbBridge";

    /** The standard adb server port; the guest client defaults to it too. */
    static final int DEFAULT_PORT = 5037;

    private static volatile AdbServer sServer;
    private static volatile FileSyncPaths sSyncPaths;
    private static volatile Context sAppContext;

    private TermdebAdbBridge() {
    }

    /** Start the bridge. Safe to call repeatedly; non-fatal on failure. */
    public static synchronized void start(Context context) {
        Context app = context.getApplicationContext();
        sAppContext = app;

        // Backing services that need an application context.
        PackageInstallerBridge.init(app);
        sSyncPaths = new FileSyncPaths(app);

        // Network transports: registry init + config-driven reconnect (spec
        // §19: restore saved ADB configuration after app/OS restart).
        AdbTransportManager.init(app);
        AdbTransportManager.boot();

        // Wireless Debugging (Android 11+ TLS pairing): registry init plus
        // reconnect to previously paired devices whose ADB port is known.
        com.termux.app.adb.wireless.WirelessTransportManager.init(app);
        com.termux.app.adb.wireless.WirelessTransportManager.boot();

        // Settings-backed Wireless Debugging enabler (WRITE_SECURE_SETTINGS
        // path for devices that gate the Settings toggle on Wi-Fi).
        com.termux.app.adb.wireless.WirelessDebuggingEnabler.init(app);

        // Enable debug logging if the app's log level is VERBOSE.
        try {
            com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences prefs =
                com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences.build(app);
            if (prefs != null && prefs.getLogLevel() >= com.termux.shared.logger.Logger.LOG_LEVEL_VERBOSE) {
                HostServices.setDebug(true);
            }
        } catch (Throwable e) {
            // Ignore; debug will remain off.
        }

        if (sServer != null && sServer.isRunning()) return;

        AdbServer server = new AdbServer(DEFAULT_PORT);
        if (server.start()) {
            sServer = server;
            return;
        }
        // Port busy: likely our own previous instance while the app was being
        // restarted. Give it a short grace period to release, then retry once.
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        AdbServer retry = new AdbServer(DEFAULT_PORT);
        if (retry.start()) {
            sServer = retry;
        } else {
            Logger.logError(LOG_TAG,
                "ADB bridge could not bind 127.0.0.1:" + DEFAULT_PORT
                    + " — guest adb clients will see standard connection errors");
        }
    }

    /** Stop the bridge and release the port (also closes forward listeners). */
    public static synchronized void stop(Context context) {
        if (sServer != null) {
            sServer.stop();
            sServer = null;
        }
    }

    /**
     * Rebind 127.0.0.1:5037 shortly after `adb kill-server` tore it down,
     * mirroring how a real adb environment restarts its server daemon on the
     * next client command. Runs on a daemon thread so the host:kill connection
     * can unwind first and release the port.
     */
    static void requestServerRestart() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (TermdebAdbBridge.class) {
                if (sServer == null || !sServer.isRunning()) {
                    Context app = sAppContext;
                    if (app != null) start(app);
                }
            }
        }, "adb-server-restart");
        t.setDaemon(true);
        t.start();
    }

    /** Whether the bridge server is currently accepting connections. */
    static boolean serverRunning() {
        AdbServer server = sServer;
        return server != null && server.isRunning();
    }

    /** Sync path resolver for FileSyncService (adb push/pull targets). */
    static FileSyncPaths syncPaths() {
        return sSyncPaths;
    }

    /** Application context for Java-backed command emulation; may be null. */
    static Context appContext() {
        return sAppContext;
    }

    // Logging helpers keep the adb package classes free of direct Logger use.

    public static void logInfo(String tag, String message) {
        Logger.logInfo(tag, message);
    }

    public static void logWarn(String tag, String message) {
        Logger.logWarn(tag, message);
    }

    public static void logError(String tag, String message) {
        Logger.logError(tag, message);
    }

    public static void logDebug(String tag, String message) {
        Logger.logDebug(tag, message);
    }

    /**
     * Resolves adb sync paths to what the app can honestly access.
     *
     * - /sdcard and /storage/emulated/0  -> real shared storage (subject to
     *   the app's own storage permissions, exactly like `adb push` on a real
     *   device is subject to the shell user's permissions).
     * - /data/local/tmp                  -> an app-owned scratch directory
     *   (the same role /data/local/tmp has for shell on a real device).
     *
     * Anything else is out of the app's sandbox: resolve() returns null and
     * the sync service answers with a genuine "Permission denied" style FAIL
     * frame instead of pretending access.
     */
    public static final class FileSyncPaths {

        private final File mStorage;
        private final File mLocalTmp;

        FileSyncPaths(Context context) {
            this(Environment.getExternalStorageDirectory(),
                new File(context.getFilesDir(), "adb/data/local/tmp"));
        }

        /** Direct-roots constructor (also used by the standalone test harness). */
        FileSyncPaths(File storage, File localTmp) {
            mStorage = storage != null ? storage : new File("/sdcard");
            mLocalTmp = localTmp;
            //noinspection ResultOfMethodCallIgnored
            mLocalTmp.mkdirs();
        }

        /** Map a device-side path to a readable/writable File, or null. */
        public File resolve(String remotePath) {
            if (remotePath == null) return null;
            String p = remotePath.trim();
            if (p.isEmpty()) return null;

            File root;
            String base;
            if (p.equals("/sdcard") || p.startsWith("/sdcard/")) {
                root = mStorage;
                base = p.substring("/sdcard".length());
            } else if (p.equals("/storage/emulated/0") || p.startsWith("/storage/emulated/0/")) {
                root = mStorage;
                base = p.substring("/storage/emulated/0".length());
            } else if (p.equals("/data/local/tmp") || p.startsWith("/data/local/tmp/")) {
                root = mLocalTmp;
                base = p.substring("/data/local/tmp".length());
            } else {
                return null;
            }

            while (base.startsWith("/")) base = base.substring(1);
            File resolved = base.isEmpty() ? root : new File(root, base);

            // Containment check so ".." traversal cannot escape the mapping.
            try {
                String canonical = resolved.getCanonicalPath();
                String rootCanonical = root.getCanonicalPath();
                if (!canonical.equals(rootCanonical)
                    && !canonical.startsWith(rootCanonical + File.separator)) {
                    return null;
                }
            } catch (IOException e) {
                return null;
            }
            return resolved;
        }

        /** The app-owned /data/local/tmp equivalent (used by shell env). */
        public File localTmp() {
            return mLocalTmp;
        }
    }
}
