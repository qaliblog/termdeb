package com.termux.app.adb;

/**
 * JNI wrapper around libadbpty (app/src/main/cpp/adb/adb_pty_jni.c).
 *
 * Creates PTY-backed child processes for the bridge's `shell,pty` service,
 * the same way terminal-emulator's termux.c does for interactive sessions,
 * but without depending on the package-private JNI class there.
 */
final class AdbPty {

    private static final boolean sLoaded = load();

    private static boolean load() {
        try {
            System.loadLibrary("adbpty");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private AdbPty() {
    }

    /** Whether libadbpty is available (fallback to pipes if not). */
    static boolean isLoaded() {
        return sLoaded;
    }

    /**
     * Fork a child attached to a new PTY.
     *
     * @param cmd  argv, first entry must be an absolute path
     * @param env  environment entries ("NAME=value"), may be null
     * @return {masterFd, pid} or {-errno, -errno} on failure
     */
    static native int[] nativeForkPty(String[] cmd, String[] env, int rows, int cols);

    /** ioctl(masterFd, TIOCSWINSZ, {rows, cols}); returns 0 or -errno. */
    static native int nativeSetWindowSize(int masterFd, int rows, int cols);

    /** waitpid(pid) — returns the raw status, or -1 if not a child / ECHILD. */
    static native int nativeWaitFor(int pid);

    /** read(fd, buf, len); returns bytes read, 0 on EOF, or -errno. */
    static native int nativeReadFd(int fd, byte[] buf, int len);

    /** write(fd, buf, len); returns bytes written, or -errno. */
    static native int nativeWriteFd(int fd, byte[] buf, int len);

    /** close(fd); returns 0 or -errno. */
    static native int nativeCloseFd(int fd);

    /** kill(pid, SIGKILL); returns 0 or -errno. */
    static native int nativeKillPid(int pid);

    /** read() a master fd, mapping errors to IOException. */
    static int readFd(int fd, byte[] buf) throws java.io.IOException {
        int n = nativeReadFd(fd, buf, buf.length);
        if (n >= 0) return n;
        if (n == -11 /* EAGAIN */ || n == -4 /* EINTR */) return 0;
        throw new java.io.IOException("read failed: errno " + -n);
    }

    static void closeFd(int fd) {
        if (fd >= 0) nativeCloseFd(fd);
    }

    static void killPid(int pid) {
        if (pid > 0) nativeKillPid(pid);
    }

    /** WEXITSTATUS(status) for a normal exit, or 0x80 | signum for signal death. */
    static int exitCode(int status) {
        if (status < 0) return 255;
        if ((status & 0x7f) == 0) return (status >> 8) & 0xff;
        return 0x80 | (status & 0x7f);
    }
}
