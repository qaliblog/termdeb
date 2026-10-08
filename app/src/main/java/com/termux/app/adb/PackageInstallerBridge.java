package com.termux.app.adb;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Thin synchronous wrapper over android.content.pm.PackageInstaller for the
 * bridge's `cmd package install-*` emulation.
 *
 * PackageInstaller.commit()/uninstall() report results asynchronously through
 * an IntentSender; real `cmd package install-commit` blocks until that status
 * arrives. This class reproduces that synchronous contract with a
 * broadcast-backed status receiver and a latch.
 *
 * Session writes read exactly the declared byte count from the stream: the
 * client half-closes its side after sending the APK body, so the read cannot
 * rely on EOF (matching how real cmd package streams sessions).
 *
 * All verdicts come from Android itself (user consent, signatures,
 * INSTALL_FAILED_* codes). Nothing here bypasses any security mechanism; when
 * Android refuses an operation the genuine status message is surfaced.
 */
final class PackageInstallerBridge {

    private static final String LOG_TAG = TermdebAdbBridge.LOG_TAG;
    private static final String ACTION_INSTALL_STATUS = "com.termux.app.adb.INSTALL_STATUS";
    /** Real install-commit waits for the user; bound the wait generously. */
    private static final long COMMIT_TIMEOUT_SECONDS = 600;

    private static volatile Context sContext;

    private PackageInstallerBridge() {
    }

    /** Called once by TermdebAdbBridge with the application context. */
    static void init(Context context) {
        sContext = context.getApplicationContext();
    }

    static boolean isSupported() {
        return sContext != null && Build.VERSION.SDK_INT >= 21;
    }

    private static PackageInstaller packageInstaller() {
        return sContext.getPackageManager().getPackageInstaller();
    }

    static int createSession(long sizeBytes) throws Exception {
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setSize(sizeBytes);
        return packageInstaller().createSession(params);
    }

    /**
     * Stream the APK body into the session. Reads exactly expectedSize bytes
     * (the client's `-S` value), since the client half-closes after sending
     * and the stream never reaches EOF while the status line is pending.
     * expectedSize <= 0 falls back to read-to-EOF.
     */
    static long writeSession(int sessionId, InputStream in, long expectedSize) throws Exception {
        PackageInstaller.Session session = null;
        try {
            session = packageInstaller().openSession(sessionId);
            OutputStream os = session.openWrite("base.apk", 0, expectedSize);
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            if (expectedSize > 0) {
                long remaining = expectedSize;
                while (remaining > 0) {
                    int want = (int) Math.min(buf.length, remaining);
                    int n = in.read(buf, 0, want);
                    if (n < 0) {
                        throw new EOFException("APK stream ended after " + total
                            + " of " + expectedSize + " bytes");
                    }
                    os.write(buf, 0, n);
                    total += n;
                    remaining -= n;
                }
            } else {
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    total += n;
                }
            }
            session.fsync(os);
            os.close();
            return total;
        } finally {
            if (session != null) session.close();
        }
    }

    static void commitSession(int sessionId) throws Exception {
        StatusWaiter waiter = newStatusWaiter("install", sessionId);
        PackageInstaller.Session session = null;
        try {
            session = packageInstaller().openSession(sessionId);
            session.commit(waiter.mSender);
        } finally {
            if (session != null) session.close();
        }
        String error = waiter.await();
        if (error != null) throw new InstallFailedException(error);
    }

    static void abandonSession(int sessionId) throws Exception {
        packageInstaller().abandonSession(sessionId);
    }

    /**
     * Uninstall a package. keepData is accepted for signature compatibility
     * but is not honored: `adb uninstall -k` is refused by the official client
     * itself (it prints the retention warning and exits 1 without contacting
     * the device), so the bridge never receives a keep-data request.
     */
    static void uninstall(String packageName, boolean keepData) throws Exception {
        StatusWaiter waiter = newStatusWaiter("uninstall", packageName.hashCode());
        packageInstaller().uninstall(packageName, waiter.mSender);
        String error = waiter.await();
        if (error != null) throw new InstallFailedException(error);
    }

    /** Thrown with the genuine status message Android reported. */
    @SuppressWarnings("serial")
    static final class InstallFailedException extends Exception {
        InstallFailedException(String message) {
            super(message);
        }
    }

    /**
     * Registers a receiver for the status broadcast and builds the matching
     * IntentSender, mirroring adb's own install status plumbing.
     */
    private static StatusWaiter newStatusWaiter(String kind, int requestCode) throws Exception {
        Context ctx = sContext;
        Intent intent = new Intent(ACTION_INSTALL_STATUS)
            .setPackage(ctx.getPackageName());
        int flags = 0;
        if (Build.VERSION.SDK_INT >= 31) {
            // PackageInstaller mutates the intent to attach status extras, so
            // the PendingIntent must be mutable on Android 12+.
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(ctx,
            REQUEST_CODE_SEQUENCER.updateAndGet(v -> (v + 1) % 100000), intent, flags);

        final CountDownLatch latch = new CountDownLatch(1);
        final String[] statusMessage = {null};
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent data) {
                if (data == null || !ACTION_INSTALL_STATUS.equals(data.getAction())) return;
                int status = data.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // A confirmation dialog was pushed. Real `cmd package` on a
                    // device launched from adb shell sees the same thing; the
                    // install completes when the user approves. Keep waiting
                    // for the final status broadcast.
                    return;
                }
                if (status != PackageInstaller.STATUS_SUCCESS) {
                    String msg = data.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    statusMessage[0] = msg != null ? msg : ("status " + status);
                }
                latch.countDown();
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_INSTALL_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, filter);
        }
        return new StatusWaiter(pi.getIntentSender(), latch, statusMessage, ctx, receiver);
    }

    private static final class StatusWaiter {
        final android.content.IntentSender mSender;
        final CountDownLatch mLatch;
        final String[] mStatusMessage;
        final Context mCtx;
        final BroadcastReceiver mReceiver;

        StatusWaiter(android.content.IntentSender sender, CountDownLatch latch,
                     String[] statusMessage, Context ctx, BroadcastReceiver receiver) {
            mSender = sender;
            mLatch = latch;
            mStatusMessage = statusMessage;
            mCtx = ctx;
            mReceiver = receiver;
        }

        /** Returns null on success, or the failure message. */
        String await() throws Exception {
            try {
                boolean done = mLatch.await(COMMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!done) throw new IOException("timed out waiting for installer status");
            } finally {
                try {
                    mCtx.unregisterReceiver(mReceiver);
                } catch (Exception ignored) {
                }
            }
            return mStatusMessage[0];
        }
    }

    /** Unique-enough request codes so PendingIntents are not reused in-flight. */
    private static final java.util.concurrent.atomic.AtomicInteger REQUEST_CODE_SEQUENCER =
        new java.util.concurrent.atomic.AtomicInteger(0);
}
