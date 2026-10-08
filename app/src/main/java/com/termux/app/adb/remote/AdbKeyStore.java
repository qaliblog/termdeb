/*
 * TermDeb ADB bridge — ADB RSA key persistence.
 *
 * The private key stays in the app's own private storage (MODE_PRIVATE inside
 * the app sandbox — NOT in the Debian rootfs, which is user-inspectable).
 * This is the "Android-private app storage mechanism" the architecture doc
 * calls for; the guest receives only what it needs: whatever it can do with
 * the bridge over 127.0.0.1:5037, never the key material itself.
 *
 * The same key is used for every network transport, exactly like
 * $HOME/.android/adbkey on a workstation: the user authorizes the TermDeb key
 * once per remote device (RSA dialog) or pairs once per Wireless Debugging
 * endpoint, and TermDeb is then authorized until the user revokes it.
 */
package com.termux.app.adb.remote;

import android.content.Context;

import com.termux.app.adb.TermdebAdbBridge;

import java.io.File;
import java.nio.charset.StandardCharsets;

/** Loads or creates the app-owned ADB RSA key pair. */
public final class AdbKeyStore {

    private static final String KEY_FILE = "adbkey.pkcs8";
    private static final Object sLock = new Object();

    private static volatile AdbKeyPair sPair;

    private AdbKeyStore() {
    }

    /** Get (or lazily create) the app's ADB key pair. Blocking on first call. */
    public static AdbKeyPair get(Context context) throws Exception {
        AdbKeyPair pair = sPair;
        if (pair != null) return pair;
        synchronized (sLock) {
            if (sPair != null) return sPair;
            File file = keyFile(context);
            if (file.isFile()) {
                try {
                    byte[] pkcs8 = base64Decode(
                        new String(java.nio.file.Files.readAllBytes(file.toPath()),
                            StandardCharsets.US_ASCII).trim());
                    sPair = AdbKeyPair.load(pkcs8);
                    TermdebAdbBridge.logInfo(TermdebAdbBridge.LOG_TAG,
                        "ADB key loaded (" + sPair.getFingerprint() + ")");
                    return sPair;
                } catch (Exception e) {
                    // Corrupt key: fall through and regenerate.
                    TermdebAdbBridge.logWarn(TermdebAdbBridge.LOG_TAG,
                        "ADB key unreadable, regenerating: " + e.getMessage());
                }
            }
            AdbKeyPair generated = AdbKeyPair.generate();
            java.nio.file.Files.write(file.toPath(),
                base64Encode(generated.getPrivateKeyPkcs8()).getBytes(StandardCharsets.US_ASCII));
            boolean ok = file.setReadable(false, false) && file.setReadable(true, true);
            if (!ok) {
                TermdebAdbBridge.logWarn(TermdebAdbBridge.LOG_TAG,
                    "ADB key file permissions could not be tightened");
            }
            sPair = generated;
            TermdebAdbBridge.logInfo(TermdebAdbBridge.LOG_TAG,
                "ADB key generated (" + sPair.getFingerprint() + ")");
            return sPair;
        }
    }

    /** The public key line shown in the Settings UI for the RSA dialog. */
    public static String publicKeyLine(Context context) throws Exception {
        return get(context).getAndroidPubkeyLine("termdeb");
    }

    private static File keyFile(Context context) {
        File dir = new File(context.getFilesDir(), "adb");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            TermdebAdbBridge.logWarn(TermdebAdbBridge.LOG_TAG,
                "Could not create " + dir.getAbsolutePath());
        }
        return new File(dir, KEY_FILE);
    }

    private static String base64Encode(byte[] data) {
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
    }

    private static byte[] base64Decode(String data) {
        return android.util.Base64.decode(data, android.util.Base64.NO_WRAP);
    }
}
