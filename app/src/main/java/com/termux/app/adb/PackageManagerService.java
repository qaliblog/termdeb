package com.termux.app.adb;

import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * `cmd package install-*` session emulation backed by the real Android
 * PackageInstaller API (API 21+, every device TermDeb runs on).
 *
 * The streamed-install line protocol matches real `cmd package`:
 *   create  -> "Success [<sessionId>]" or "Error: ..." (+ "Exception: ...")
 *   write   -> "Success" / "Error: ..."
 *   commit  -> "Success" / "Failure [<message>]" (honest INSTALL_FAILED_* text)
 *   abandon -> "Success" / "Error: ..."
 *   uninstall -> "Success" / "Error: ..."
 *   install -> "Success" / "Failure [<message>]" (one-shot streamed install,
 *              the single command `adb install <apk>` sends: `install ... -S
 *              <size>` with the APK body on stdin, run as create+write+commit)
 *
 * On a user build, installation still requires the standard user consent
 * dialog and possibly REQUEST_INSTALL_PACKAGES; when Android refuses, the
 * genuine error text is streamed back and the client exits non-zero. No
 * security bypass is attempted anywhere in this class.
 */
final class PackageManagerService {

    private static final String LOG_TAG = TermdebAdbBridge.LOG_TAG;

    /** Install sessions keyed by id; the bridge serves one client at a time. */
    private static final Map<Integer, SessionState> sSessions = new HashMap<>();

    /**
     * Entry point for `exec:cmd package install-...` streams. Writes the
     * protocol output to `out`, consuming `in` for install-write payloads.
     */
    static void handleCmdPackage(String args, InputStream in, OutputStream out) {
        // args excludes the leading "cmd "; tokens[0] = "package".
        String[] tokens = splitArgs(args);
        String sub = tokens.length > 1 ? tokens[1] : "";

        switch (sub) {
            case "install":
                handleOneShotInstall(tokens, in, out);
                break;
            case "install-create":
                handleCreate(tokens, out);
                break;
            case "install-write":
                handleWrite(tokens, in, out);
                break;
            case "install-commit":
                handleCommit(tokens, out);
                break;
            case "install-abandon":
                handleAbandon(tokens, out);
                break;
            case "uninstall":
                handleUninstall(tokens, out);
                break;
            default:
                writeLine(out, "Error: unknown command " + (sub.isEmpty() ? "(none)" : sub));
                break;
        }
    }

    /**
     * Split a `cmd package ...` argument string the way a shell would.
     *
     * Real `cmd` runs through the shell, so quoting never reaches it. Our
     * emulation sees the raw service string instead, and the adb client
     * single-quotes the arguments it passes through (its escape_arg wraps each
     * one in '...', writing an embedded quote as '\''), which is why
     * `adb install` arrives as `cmd package 'install' -S <size>`. Unquoted
     * words are the ones the client appends itself, such as -S and its value.
     */
    private static String[] splitArgs(String args) {
        java.util.List<String> words = new java.util.ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        boolean inQuote = false;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (inQuote) {
                if (c == '\'') {
                    inQuote = false;        // closing quote ends the quoted run
                } else {
                    word.append(c);         // quoted text is taken verbatim
                }
            } else if (c == '\'') {
                inQuote = true;             // opening quote: no word split inside
                inWord = true;
            } else if (c == '\\' && i + 1 < args.length()) {
                inWord = true;              // \x escapes x, as in '\''
                word.append(args.charAt(++i));
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
            } else {
                inWord = true;
                word.append(c);
            }
        }
        // A word still open here also covers an unterminated quote.
        if (inWord) words.add(word.toString());
        return words.toArray(new String[0]);
    }

    /**
     * `cmd package install [flags] -S <size>`: the one-shot streamed install
     * AOSP's doRunInstall implements and `adb install <apk>` uses. The client
     * sends the APK body on the same stream right after the command, so the
     * bytes are read from `in` exactly like an install-write payload; the
     * session is then created, written and committed in one go.
     *
     * Flags the client passes through (`-r`, `-d`, `-g`, `-i`, `--user`, ...)
     * are accepted and ignored: SessionParams exposes no public way to set the
     * install flags, and an app-uid install has to clear the same platform
     * checks a real device would apply anyway. The verdict is Android's.
     */
    private static void handleOneShotInstall(String[] tokens, InputStream in, OutputStream out) {
        long size = -1;
        for (int i = 0; i < tokens.length; i++) {
            if ("-S".equals(tokens[i]) && i + 1 < tokens.length) {
                try {
                    // The client appends -S last so it overrides any earlier
                    // value (install_app_streamed does the same).
                    size = Long.parseLong(tokens[i + 1]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (size < 0) {
            // AOSP doRunInstall: a stdin install without a size is this error.
            writeLine(out, "Error: must either specify a package size or an APK file");
            writeException(out, null);
            return;
        }
        if (!PackageInstallerBridge.isSupported()) {
            // Drain first: the client is already streaming the APK body, and
            // closing on it would surface as a copy error instead of this.
            drain(in, size);
            writeLine(out, "Error: PackageInstaller not available on this platform");
            writeException(out, null);
            return;
        }
        int id;
        try {
            id = PackageInstallerBridge.createSession(size);
        } catch (Exception e) {
            drain(in, size);
            writeLine(out, "Error: " + e.getMessage());
            writeException(out, e);
            return;
        }
        try {
            PackageInstallerBridge.writeSession(id, in, size);
        } catch (Exception e) {
            abandonQuietly(id);
            writeLine(out, "Error: " + e.getMessage());
            writeException(out, e);
            return;
        }
        try {
            // The real installer runs here (session commit + status broadcast),
            // so signature checks and INSTALL_FAILED_* verdicts are Android's.
            PackageInstallerBridge.commitSession(id);
            writeLine(out, "Success");
        } catch (Exception e) {
            // AOSP abandons the session when the commit fails, and reports the
            // installer's status message as "Failure [<message>]".
            abandonQuietly(id);
            writeLine(out, "Failure [" + failureText(e) + "]");
        }
    }

    private static void handleCreate(String[] tokens, OutputStream out) {
        long size = -1;
        for (int i = 0; i < tokens.length; i++) {
            if ("-S".equals(tokens[i]) && i + 1 < tokens.length) {
                try {
                    size = Long.parseLong(tokens[i + 1]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (size < 0) {
            writeLine(out, "Error: must specify APK size with -S");
            writeException(out, null);
            return;
        }
        if (!PackageInstallerBridge.isSupported()) {
            writeLine(out, "Error: PackageInstaller not available on this platform");
            writeException(out, null);
            return;
        }
        try {
            int id = PackageInstallerBridge.createSession(size);
            synchronized (sSessions) {
                sSessions.put(id, new SessionState(size));
            }
            writeLine(out, "Success [" + id + "]");
        } catch (Exception e) {
            // Honest failure: surface what Android actually said.
            writeLine(out, "Error: " + e.getMessage());
            writeException(out, e);
        }
    }

    private static void handleWrite(String[] tokens, InputStream in, OutputStream out) {
        int id = -1;
        long size = -1;
        String name = "base.apk";
        for (int i = 0; i < tokens.length; i++) {
            if ("-S".equals(tokens[i]) && i + 1 < tokens.length) {
                try {
                    size = Long.parseLong(tokens[i + 1]);
                } catch (NumberFormatException ignored) {
                }
            } else if (tokens[i].matches("\\d+")) {
                id = Integer.parseInt(tokens[i]);
            } else if (!"-".equals(tokens[i]) && !"install-write".equals(tokens[i])
                && !"package".equals(tokens[i]) && !tokens[i].startsWith("-")
                && !tokens[i].equals(name)) {
                name = tokens[i];
            }
        }
        SessionState session;
        synchronized (sSessions) {
            session = sSessions.get(id);
        }
        if (session == null) {
            writeLine(out, "Error: session " + id + " not found");
            writeException(out, null);
            return;
        }
        try {
            long written = PackageInstallerBridge.writeSession(id, in, size);
            session.mBytesWritten = written;
            writeLine(out, "Success");
        } catch (Exception e) {
            writeLine(out, "Error: " + e.getMessage());
            writeException(out, e);
        }
    }

    private static void handleCommit(String[] tokens, OutputStream out) {
        int id = findSessionId(tokens);
        SessionState session;
        synchronized (sSessions) {
            session = sSessions.get(id);
        }
        if (session == null) {
            writeLine(out, "Error: session " + id + " not found");
            writeException(out, null);
            return;
        }
        try {
            // The real Android installer flow runs here: user consent dialog,
            // signature checks, INSTALL_FAILED_* verdicts. Everything Android
            // rejects is streamed back as the genuine Error text.
            PackageInstallerBridge.commitSession(id);
            writeLine(out, "Success");
            synchronized (sSessions) {
                sSessions.remove(id);
            }
        } catch (Exception e) {
            // AOSP doCommitSession prints the installer's status message as
            // "Failure [<message>]" — the line adb echoes verbatim.
            writeLine(out, "Failure [" + failureText(e) + "]");
            synchronized (sSessions) {
                sSessions.remove(id);
            }
        }
    }

    private static void handleAbandon(String[] tokens, OutputStream out) {
        int id = findSessionId(tokens);
        boolean exists;
        synchronized (sSessions) {
            exists = sSessions.containsKey(id);
        }
        if (!exists) {
            writeLine(out, "Error: session " + id + " not found");
            writeException(out, null);
            return;
        }
        try {
            PackageInstallerBridge.abandonSession(id);
        } catch (Exception ignored) {
        }
        synchronized (sSessions) {
            sSessions.remove(id);
        }
        writeLine(out, "Success");
    }

    private static void handleUninstall(String[] tokens, OutputStream out) {
        boolean keepData = false;
        String pkg = null;
        for (String t : tokens) {
            if ("-k".equals(t)) keepData = true;
            else if (!"package".equals(t) && !"uninstall".equals(t)
                && !t.startsWith("-")) pkg = t;
        }
        if (pkg == null) {
            writeLine(out, "Error: package name required");
            writeException(out, null);
            return;
        }
        try {
            PackageInstallerBridge.uninstall(pkg, keepData);
            writeLine(out, "Success");
        } catch (Exception e) {
            writeLine(out, "Error: " + e.getMessage());
            writeException(out, e);
        }
    }

    private static int findSessionId(String[] tokens) {
        for (String t : tokens) {
            if (t.matches("\\d+")) return Integer.parseInt(t);
        }
        return -1;
    }

    /** The message AOSP places inside `Failure [...]` for a failed operation. */
    private static String failureText(Exception e) {
        String message = e.getMessage();
        return message != null ? message : e.getClass().getName();
    }

    /** Consume an unread APK payload so the client sees our error, not EPIPE. */
    private static void drain(InputStream in, long size) {
        try {
            byte[] buf = new byte[64 * 1024];
            long remaining = size;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) return;
                remaining -= n;
            }
        } catch (IOException ignored) {
        }
    }

    private static void abandonQuietly(int sessionId) {
        try {
            PackageInstallerBridge.abandonSession(sessionId);
        } catch (Exception ignored) {
        }
    }

    private static void writeLine(OutputStream out, String line) {
        try {
            out.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    /** "Exception: <class>" line, like real cmd package on a failed op. */
    private static void writeException(OutputStream out, Exception e) {
        writeLine(out, "Exception: " + (e != null ? e.getClass().getName() : "java.lang.Exception"));
    }

    /** Per-session bookkeeping. */
    private static final class SessionState {
        final long mDeclaredSize;
        long mBytesWritten;

        SessionState(long declaredSize) {
            mDeclaredSize = declaredSize;
        }
    }

    // Keep the unused-import warning away for Build, which documents intent.
    @SuppressWarnings("unused")
    private static final int UNUSED_BUILD_REF = Build.VERSION.SDK_INT;
}
