package com.termux.app.adb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Executes commands for the shell/exec device services.
 *
 * Two runners:
 * - PTY mode (interactive "shell,pty" / legacy shell): the child runs on a
 *   pseudo-terminal via AdbPty (libadbpty), like adbd's interactive shell.
 * - Pipe mode (raw/non-interactive): ProcessBuilder pipes, stderr separate
 *   (only visible to the client under shell v2).
 *
 * Every command runs the genuine Android host binaries ("sh -c"), so
 * getprop/dumpsys/logcat behavior is authentic; DeviceCommandHandlers only
 * intercepts commands that need Java-backed emulation.
 */
final class ShellRunner {

    private static final String LOG_TAG = TermdebAdbBridge.LOG_TAG;

    private final DeviceCommandHandlers mHandlers = new DeviceCommandHandlers();

    ShellRunner() {
    }

    boolean isPtySupported() {
        return AdbPty.isLoaded();
    }

    /** Env for device-side commands (what a real device's shell sees). */
    static String[] defaultEnvironment(String term) {
        String path = "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:"
            + "/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin";
        return new String[] {
            "PATH=" + path,
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "ANDROID_ART_ROOT=/apex/com.android.art",
            "ANDROID_I18N_ROOT=/apex/com.android.i18n",
            "ANDROID_TZDATA_ROOT=/apex/com.android.tzdata",
            "HOME=/",
            "USER=shell",
            "SHELL=/system/bin/sh",
            "TMPDIR=/data/local/tmp",
            "TERM=" + (term == null ? "" : term),
        };
    }

    /** Handle a command; returns null to fall back to real execution. */
    DeviceCommandHandlers.Result intercept(String command) {
        return mHandlers.handle(command);
    }

    private static ProcessBuilder baseProcess(String command) {
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
        pb.environment().clear();
        for (String kv : defaultEnvironment(null)) {
            int eq = kv.indexOf('=');
            if (eq > 0) pb.environment().put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        pb.redirectErrorStream(false);
        return pb;
    }

    /** Run in pipe mode; returns an engine that streams stdout/stderr v2 packets. */
    PipeEngine startPipe(String command) throws IOException {
        return new PipeEngine(baseProcess(command).start(), false);
    }

    /**
     * Run for the exec: service: merged stdout+stderr (like a real device,
     * where exec: has no way to separate the streams), raw byte pump.
     */
    PipeEngine startExec(String command) throws IOException {
        ProcessBuilder pb = baseProcess(command);
        pb.redirectErrorStream(true);
        return new PipeEngine(pb.start(), true);
    }

    /** Run on a PTY. Returns null if PTY mode is unavailable. */
    PtyEngine startPty(String command, String term, int rows, int cols) {
        // A missing library raises UnsatisfiedLinkError (an Error) — check the
        // load flag first so callers get the null -> pipe fallback instead of
        // the connection thread dying mid-protocol.
        if (!AdbPty.isLoaded()) {
            TermdebAdbBridge.logWarn(LOG_TAG, "PTY unavailable: libadbpty not loaded");
            return null;
        }
        int[] result = AdbPty.nativeForkPty(
            new String[] {"/system/bin/sh", "-c", command},
            defaultEnvironment(term), rows, cols);
        if (result == null || result[0] < 0) {
            TermdebAdbBridge.logWarn(LOG_TAG, "PTY fork failed: "
                + (result == null ? "jni" : ("errno " + -result[0])));
            return null;
        }
        return new PtyEngine(result[0], result[1]);
    }

    /** Streaming engine for a pipe-mode child. */
    final class PipeEngine {
        final Process mProcess;
        final boolean mMerged;

        PipeEngine(Process process, boolean merged) {
            mProcess = process;
            mMerged = merged;
        }

        /** Write client stdin bytes to the child. Returns false if stdin closed. */
        boolean writeStdin(byte[] data, int len) {
            try {
                OutputStream os = mProcess.getOutputStream();
                os.write(data, 0, len);
                os.flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        /** Close the child's stdin (shell v2 kIdCloseStdin). */
        void closeStdin() {
            try {
                mProcess.getOutputStream().close();
            } catch (IOException ignored) {
            }
        }

        /**
         * Pump the child's stdout/stderr, then send exit (v2 only). Legacy
         * clients get a raw merged stream with no exit status, matching
         * adbd's legacy shell (which is a PTY, i.e. output is merged).
         */
        void run(OutputStream out, boolean v2) throws IOException {
            InputStream stdout = mProcess.getInputStream();
            if (!v2) {
                byte[] buf = new byte[32 * 1024];
                int n;
                try {
                    while ((n = stdout.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                    if (!mMerged) {
                        InputStream stderr = mProcess.getErrorStream();
                        while ((n = stderr.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            out.flush();
                        }
                    }
                } catch (IOException ignored) {
                    // client closed
                }
                try {
                    mProcess.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    mProcess.destroy();
                }
                return;
            }
            byte[] buf = new byte[ShellProtocol.MAX_PAYLOAD];

            if (!mMerged) {
                InputStream stderr = mProcess.getErrorStream();
                Thread errThread = new Thread(() -> pump(stderr, out, ShellProtocol.ID_STDERR),
                    "adb-shell-stderr");
                errThread.setDaemon(true);
                errThread.start();
                pump(stdout, out, ShellProtocol.ID_STDOUT);
                try {
                    int exit = mProcess.waitFor();
                    errThread.join(1000);
                    ShellProtocol.writeExit(out, exit);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    mProcess.destroy();
                    ShellProtocol.writeExit(out, 255);
                }
                return;
            }
            pump(stdout, out, ShellProtocol.ID_STDOUT);
            int exit;
            try {
                exit = mProcess.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mProcess.destroy();
                exit = 255;
            }
            // exec: has no exit-status channel; the stream just ends.
        }

        /**
         * Raw byte pump for exec: (no packets, no exit status): the client
         * sees the child's raw merged output, exactly like a real device.
         */
        void runRaw(OutputStream out) throws IOException {
            InputStream stdout = mProcess.getInputStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            try {
                while ((n = stdout.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // client closed
            }
            try {
                mProcess.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mProcess.destroy();
            }
        }

        private void pump(InputStream in, OutputStream out, byte id) {
            byte[] buf = new byte[ShellProtocol.MAX_PAYLOAD];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    ShellProtocol.writePacket(out, id, buf, n);
                }
            } catch (IOException ignored) {
                // client closed or stream ended
            }
        }

        void kill() {
            mProcess.destroy();
        }
    }

    /** Streaming engine for a PTY-mode child. */
    final class PtyEngine {
        final int mMasterFd;
        final int mPid;

        PtyEngine(int masterFd, int pid) {
            mMasterFd = masterFd;
            mPid = pid;
        }

        void setWindowSize(int rows, int cols) {
            AdbPty.nativeSetWindowSize(mMasterFd, rows, cols);
        }

        /** Write client stdin bytes to the pty master. Returns 0 on success. */
        int writeStdin(byte[] data, int len) {
            return AdbPty.nativeWriteFd(mMasterFd, data, len);
        }

        /**
         * Pump PTY output until EOF, then reap the child. v2 clients get the
         * exit packet; legacy clients get a bare raw stream (no status).
         */
        int run(OutputStream out, boolean v2) throws IOException {
            byte[] buf = new byte[ShellProtocol.MAX_PAYLOAD];
            int n;
            if (!v2) {
                byte[] raw = new byte[32 * 1024];
                try {
                    while ((n = AdbPty.readFd(mMasterFd, raw)) > 0) {
                        out.write(raw, 0, n);
                        out.flush();
                    }
                } catch (IOException ignored) {
                    // client closed
                }
                AdbPty.closeFd(mMasterFd);
                AdbPty.nativeWaitFor(mPid);
                return 0;
            }
            try {
                while ((n = AdbPty.readFd(mMasterFd, buf)) > 0) {
                    ShellProtocol.writePacket(out, ShellProtocol.ID_STDOUT, buf, n);
                }
            } catch (IOException ignored) {
                // client closed
            }
            AdbPty.closeFd(mMasterFd);
            int status = AdbPty.nativeWaitFor(mPid);
            int exit = AdbPty.exitCode(status);
            ShellProtocol.writeExit(out, exit);
            return exit;
        }

        void kill() {
            AdbPty.killPid(mPid);
        }
    }
}
