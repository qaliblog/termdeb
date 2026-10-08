package com.termux.app.adb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import android.os.Build;

/**
 * Device-side shell command emulation for the bridge.
 *
 * Only commands that need Java-backed output are intercepted; everything else
 * runs the real Android binaries through /system/bin/sh so behavior is
 * genuine. Android restrictions are preserved: operations the OS would reject
 * (pm install without user consent, run-as on non-debuggable apps, dumpsys of
 * protected services) fail with honest errors via the real binaries or via
 * empty results — never fabricated success.
 */
class DeviceCommandHandlers {

    /** Result of a handler: stdout/stderr text and exit status. */
    static final class Result {
        final String mStdout;
        final String mStderr;
        final int mExit;

        Result(String stdout, String stderr, int exit) {
            mStdout = stdout;
            mStderr = stderr;
            mExit = exit;
        }
    }

    DeviceCommandHandlers() {
    }

    /**
     * Handle a shell command if it is one we emulate. Returns null when the
     * command should be executed for real via sh -c.
     */
    static Result handle(String command) {
        String trimmed = command.trim();
        // Only bare invocations are emulated. Anything the shell must compose
        // (pipes, redirection, substitution, sequencing) runs for real via
        // sh -c, otherwise `getprop | grep abi` would silently lose its pipe
        // and `pm list packages > /sdcard/x` its redirection.
        if (hasShellMeta(trimmed)) {
            return null;
        }
        if (trimmed.equals("getprop") || trimmed.startsWith("getprop ")) {
            return handleGetprop(trimmed);
        }
        if (trimmed.startsWith("pm list packages")) {
            return handlePmListPackages(trimmed);
        }
        return null;
    }

    /** Whether the shell is being asked to compose this command. */
    private static boolean hasShellMeta(String command) {
        for (int i = 0; i < command.length(); i++) {
            switch (command.charAt(i)) {
                case '|': case '&': case ';': case '<': case '>':
                case '`': case '$': case '(': case ')':
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    // ---------- getprop ----------

    /**
     * getprop backed by the system property service where possible
     * (SystemProperties is a hidden API, so we parse getprop's own real
     * output lazily and otherwise answer from build files we can read).
     * Keys the bridge virtualizes are answered consistently for both
     * `getprop` (list form) and `getprop <key>`.
     */
    private static Result handleGetprop(String command) {
        String rest = command.substring("getprop".length()).trim();
        List<String[]> props = readProperties();

        if (rest.isEmpty()) {
            // List form. The virtualized set is deliberately small (and some
            // of it — ro.secure, ro.serialno, service.adb.root — is unreadable
            // from an app uid), but the real property service knows ~2000
            // entries that agents legitimately need. Prefer the real list and
            // overlay the virtualized values on top, so nothing is hidden and
            // the bridge's coherent device identity still wins.
            Result real = runRealGetprop(null);
            if (real == null) {
                StringBuilder out = new StringBuilder();
                for (String[] kv : props) {
                    out.append("[").append(kv[0]).append("]: [").append(kv[1]).append("]\n");
                }
                return new Result(out.toString(), "", 0);
            }
            return new Result(mergeProps(real.mStdout, props), real.mStderr, real.mExit);
        }

        String[] tokens = rest.split("\\s+");
        String key = stripQuotes(tokens[0]);
        String value = propValue(props, key);

        // Keys the bridge virtualizes are answered from its own coherent set;
        // everything else is a genuine device property (ro.product.cpu.abi,
        // ro.product.board, ro.build.version.*, ...) that only the real
        // property service can supply. Deferring here keeps this handler from
        // shadowing a fully working /system/bin/getprop.
        if (value != null) {
            // Real getprop terminates the value with a newline.
            return new Result(value + "\n", "", 0);
        }

        // `getprop key default` — getprop itself prints the default when the
        // key is unset, so pass the whole argument list through unchanged.
        Result real = runRealGetprop(tokens);
        if (real != null) {
            return real;
        }
        // No real binary available (test harness): unset prints nothing, exit 0.
        String def = null;
        if (tokens.length > 1) {
            StringBuilder d = new StringBuilder();
            for (int i = 1; i < tokens.length; i++) {
                if (i > 1) d.append(' ');
                d.append(stripQuotes(tokens[i]));
            }
            def = d.toString();
        }
        return new Result(def == null || def.isEmpty() ? "" : def + "\n", "", 0);
    }

    /**
     * Run the device's real getprop. Args are passed as separate argv entries
     * (no shell involved), so keys and default values need no quoting. Returns
     * null when the binary is unavailable or cannot be started.
     */
    private static Result runRealGetprop(String[] args) {
        try {
            List<String> argv = new ArrayList<>();
            argv.add("/system/bin/getprop");
            if (args != null) {
                for (String a : args) argv.add(stripQuotes(a));
            }
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String stdout = drain(p.getInputStream());
            String stderr = drain(p.getErrorStream());
            int exit = p.waitFor();
            return new Result(stdout, stderr, exit);
        } catch (Exception e) {
            return null;
        }
    }

    private static String drain(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        return new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Real `[key]: [value]` list with the virtualized entries overlaid. */
    private static String mergeProps(String realList, List<String[]> virtualized) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (String line : realList.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("[")) continue;
            int close = t.indexOf("]: [");
            if (close <= 1 || !t.endsWith("]")) continue;
            merged.put(t.substring(1, close), t.substring(close + 4, t.length() - 1));
        }
        for (String[] kv : virtualized) {
            merged.put(kv[0], kv[1]);
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : merged.entrySet()) {
            out.append("[").append(e.getKey()).append("]: [").append(e.getValue()).append("]\n");
        }
        return out.toString();
    }

    private static String propValue(List<String[]> props, String key) {
        for (String[] kv : props) {
            if (kv[0].equals(key)) return kv[1];
        }
        return null;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '[' && s.charAt(s.length() - 1) == ']') {
            return s.substring(1, s.length() - 1);
        }
        if (s.length() >= 2 && (s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** The virtualized properties: what the bridge honestly is. */
    private static List<String[]> baseProps() {
        List<String[]> props = new ArrayList<>();
        put(props, "ro.build.version.release", android.os.Build.VERSION.RELEASE);
        put(props, "ro.build.version.sdk", String.valueOf(android.os.Build.VERSION.SDK_INT)); // sic: Build.VERSION is an alias to Build_VERSION in stubs
        put(props, "ro.build.version.security_patch", android.os.Build.VERSION.SECURITY_PATCH);
        put(props, "ro.product.model", Build.MODEL);
        put(props, "ro.product.brand", Build.BRAND);
        put(props, "ro.product.manufacturer", Build.MANUFACTURER);
        put(props, "ro.product.device", Build.DEVICE);
        put(props, "ro.product.name", Build.PRODUCT);
        put(props, "ro.build.display.id", Build.DISPLAY);
        put(props, "ro.build.fingerprint", Build.FINGERPRINT);
        put(props, "ro.build.type", Build.TYPE);
        put(props, "ro.build.tags", Build.TAGS);
        put(props, "ro.hardware", Build.HARDWARE);
        put(props, "ro.board.platform", Build.BOARD);
        put(props, "ro.bootmode", "unknown");
        put(props, "ro.secure", "1");
        put(props, "ro.debuggable", "0"); // user build: adb root honestly refused
        put(props, "service.adb.root", "0");
        put(props, "persist.sys.usb.config", "adb");
        put(props, "ro.product.first_api_level", String.valueOf(android.os.Build.VERSION.SDK_INT));
        return props;
    }

    private static void put(List<String[]> props, String k, String v) {
        if (v != null && !v.isEmpty()) props.add(new String[] {k, v});
    }

    /**
     * Property list. Files that real getprop reads are parsed when readable
     * (rare from an app uid); the virtualized set above is always included so
     * an agent sees a coherent user-build device.
     */
    private static List<String[]> readProperties() {
        List<String[]> props = baseProps();
        for (String path : new String[] {"/system/build.prop", "/system/etc/prop.default",
            "/vendor/build.prop", "/odm/etc/build.prop"}) {
            parseProps(path, props);
        }
        return props;
    }

    private static void parseProps(String path, List<String[]> out) {
        java.io.File f = new java.io.File(path);
        if (!f.isFile() || !f.canRead()) return;
        try {
            List<String> lines =
                java.nio.file.Files.readAllLines(f.toPath());
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                String key = stripQuotes(t.substring(0, eq).trim());
                String value = stripQuotes(t.substring(eq + 1).trim());
                out.removeIf(kv -> kv[0].equals(key));
                out.add(new String[] {key, value});
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- pm list packages ----------

    /**
     * pm list packages — package enumeration via PackageManager, the same
     * source real pm uses through binder. Flags: -f (apk paths), -s (system),
     * -3 (third party), -d (disabled), -e (enabled), -u (include uninstalled),
     * plus a filter substring.
     */
    private static Result handlePmListPackages(String command) {
        String rest = command.substring("pm list packages".length()).trim();
        boolean showApks = hasFlag(rest, "-f");
        boolean showOnlySystem = hasFlag(rest, "-s");
        boolean showOnlyThirdParty = hasFlag(rest, "-3");
        boolean showDisabled = hasFlag(rest, "-d");
        boolean showEnabled = hasFlag(rest, "-e");

        String filter = extractFilter(rest);

        android.content.Context ctx = TermdebAdbBridge.appContext();
        StringBuilder out = new StringBuilder();
        if (ctx == null) {
            // No context (bridge used before Application init): the honest
            // answer is an empty package list, exit 0, like a shell that can
            // see no packages.
            return new Result("", "", 0);
        }
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        List<android.content.pm.ApplicationInfo> apps =
            pm.getInstalledApplications(0);
        for (android.content.pm.ApplicationInfo app : apps) {
            boolean system = (app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            if (showOnlySystem && !system) continue;
            if (showOnlyThirdParty && system) continue;
            boolean enabled = applicationInfoEnabled(app);
            if (showDisabled && enabled) continue;
            if (showEnabled && !enabled) continue;
            if (filter != null && !app.packageName.contains(filter)) continue;
            if (showApks) {
                String path = app.sourceDir != null ? app.sourceDir : "?";
                out.append("package:").append(path).append('=')
                    .append(app.packageName).append('\n');
            } else {
                out.append("package:").append(app.packageName).append('\n');
            }
        }
        return new Result(out.toString(), "", 0);
    }

    private static boolean hasFlag(String rest, String flag) {
        for (String token : rest.split("\\s+")) {
            if (token.equals(flag)) return true;
        }
        return false;
    }

    private static String extractFilter(String rest) {
        // Last token that does not start with '-' and is not a flag value.
        String[] tokens = rest.trim().split("\\s+");
        String filter = null;
        for (String t : tokens) {
            if (!t.isEmpty() && !t.startsWith("-")) filter = t;
        }
        return filter;
    }

    private static boolean applicationInfoEnabled(android.content.pm.ApplicationInfo app) {
        return app.enabled;
    }
}
