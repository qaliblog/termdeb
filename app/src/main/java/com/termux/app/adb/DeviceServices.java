package com.termux.app.adb;

import android.content.Context;

import com.termux.app.adb.remote.AdbTransportManager;
import com.termux.app.adb.remote.RemoteDevice;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Device service dispatcher (the "adbd" role of the bridge).
 *
 * Parses device service strings (AOSP docs/dev/services.md) and hands each
 * connection to the matching implementation:
 *
 *   shell[,v2][,TERM=x][,pty|raw]:<command>   ShellRunner (+ DeviceCommandHandlers)
 *   exec:<command>                            raw pipe (stdout+stderr merged),
 *                                             incl. "cmd package ..." installs
 *   sync:                                     FileSyncService (push/pull)
 *   tcp:<port>, local:..., localabstract:...  transport connect + relay
 *   track-jdwp                                empty stream (no debuggable VMs)
 *   reverse:forward:...                       device-side reverse listener
 *
 * Every device service is a raw stream after the OKAY activation, so this
 * class always signals {@link NoQueryTailException} on success — the smart
 * protocol "0000" tail applies only to host query services.
 *
 * Commands run as this app's own UID on the real Android host. Android's own
 * permission checks are the security boundary and are never bypassed: what
 * Android rejects (pm install without consent, dumpsys of protected services,
 * force-stop of other packages, ...) fails here exactly as it fails for the
 * app, with genuine error text and non-zero exit status.
 */
final class DeviceServices {

    static final String SERIAL = HostServices.SERIAL;

    /** Thrown by raw stream services so the smart-protocol tail is skipped. */
    static final class NoQueryTailException extends RuntimeException {
        NoQueryTailException() {
            // Superclass fills in the stack trace lazily; these are control flow.
            super(null, null, false, false);
        }
    }

    DeviceServices() {
    }

    private static void d(String svc, String msg, String ctx) {
        if (HostServices.isDebugEnabled()) TermdebAdbBridge.logDebug("DeviceServices", svc + ": " + msg + " ctx=" + ctx);
    }


    /**
     * Payload for host:devices[-l] — the live device list: the bridge's own
     * virtual device (always present, the TermDeb environment itself) plus
     * every currently-online network transport, mirroring what a workstation
     * adb server reports.
     */
    static String devicesList(boolean longForm) {
        StringBuilder sb = new StringBuilder();
        if (longForm) {
            sb.append(SERIAL).append("\tdevice product:termdeb model:termdeb device:termdeb")
                .append(" transport_id:1\n");
        } else {
            sb.append(SERIAL).append("\tdevice\n");
        }
        for (RemoteDevice d : AdbTransportManager.devices()) {
            if (!d.isOnline()) continue;
            if (longForm) {
                sb.append(d.getSerial()).append("\tdevice product:termdeb_bridge model:termdeb_bridge")
                    .append(" device:termdeb_bridge transport_id:")
                    .append(TransportSelection.transportIdOf(d)).append('\n');
            } else {
                sb.append(d.getSerial()).append("\tdevice\n");
            }
        }
        // Wireless Debugging (Android 11+ TLS) transports list alongside the
        // legacy TCP ones — indistinguishable from a workstation's viewpoint.
        for (RemoteDevice d : com.termux.app.adb.wireless.WirelessTransportManager.devices()) {
            if (!d.isOnline()) continue;
            if (longForm) {
                sb.append(d.getSerial()).append("\tdevice product:termdeb_bridge model:termdeb_bridge")
                    .append(" device:termdeb_bridge transport_id:")
                    .append(TransportSelection.transportIdOf(d)).append('\n');
            } else {
                sb.append(d.getSerial()).append("\tdevice\n");
            }
        }
        return sb.toString();
    }

    /** Device state for a serial (host:get-state). */
    static String stateForSerial(String serial) {
        if (serial == null || serial.isEmpty() || SERIAL.equals(serial)) {
            return "device";
        }            RemoteDevice d = AdbTransportManager.bySpec(serial);
            if (d == null || !d.isOnline()) {
                d = com.termux.app.adb.wireless.WirelessTransportManager.bySpec(serial);
            }
            return (d != null && d.isOnline()) ? "device" : "unknown <serial>";
    }

    /** Dispatch a device service string. Always a raw stream on success. */
    static boolean handleDeviceService(Socket socket, InputStream in, OutputStream out,
                                       String service) throws IOException {
        return handleDeviceService(socket, in, out, service, null);
    }

    /**
     * Dispatch a device service string with the serial the client selected
     * (null = default). Only the transport-connect family consults it; shell,
     * exec, sync and friends run on this device regardless.
     */
    static boolean handleDeviceService(Socket socket, InputStream in, OutputStream out,
                                       String service, String serial) throws IOException {
        d(service, "device service dispatch", service);
        if (service.equals("sync:")) {
            HostServices.writeOkay(out);
            FileSyncService.serve(in, out);
            throw new NoQueryTailException();
        }
        if (service.startsWith("shell")) {
            return handleShell(in, out, service);
        }
        if (service.startsWith("exec:")) {
            return handleExec(in, out, service.substring("exec:".length()));
        }
        if (service.startsWith("reverse:")) {
            handleReverse(out, service.substring("reverse:".length()));
            return true;
        }
        if (service.equals("track-jdwp")) {
            // No debuggable VMs are visible to this bridge: an empty stream.
            HostServices.writeOkay(out);
            throw new NoQueryTailException();
        }
        if (service.equals("root:") || service.equals("unroot:")) {
            // The bridge never runs adbd as root, matching a user-build device.
            // adb root() checks the reply for "restarting"; any other text makes
            // it return success after printing this line.
            HostServices.writeOkay(out);
            out.write("adbd cannot run as root in production builds\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            throw new NoQueryTailException();
        }
        if (service.startsWith("reboot:")) {
            // Rebooting the Android host from an app is impossible; reply like
            // adbd does (bare OKAY, then EOF) so clients don't hang. The client
            // just dumps the stream and exits.
            d(service, "reboot requested (ignored)", service);
            HostServices.writeOkay(out);
            throw new NoQueryTailException();
        }
        if (service.startsWith("remount:") || service.startsWith("disable-verity:")
            || service.startsWith("enable-verity:")) {
            // Without root these always fail; reply the activation OKAY plus
            // the plain-text status the client prints.
            HostServices.writeOkay(out);
            out.write("Not running as root\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            throw new NoQueryTailException();
        }
        if (service.startsWith("tcpip:")) {
            HostServices.writeOkay(out);
            out.write(("restarting in TCP mode (unsupported: network transports "
                + "are not available in this bridge)\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            throw new NoQueryTailException();
        }
        if (service.startsWith("tcp:") || service.startsWith("local:")
            || service.startsWith("localabstract:")) {
            return handleTransportConnect(serial, in, out, service);
        }
        return false;
    }

    // ---------- shell ----------

    /**
     * "shell[,v2][,TERM=x][,pty|raw]:<command>"
     *
     * v2: shell-protocol packets (stdin/stdout/stderr/exit/winsize).
     * legacy (no v2): raw byte stream, PTY always, no exit status.
     */
    private static boolean handleShell(InputStream in, OutputStream out, String service)
        throws IOException {
        int colon = service.indexOf(':');
        if (colon < 0) return false;
        String args = service.substring("shell".length(), colon);
        String command = service.substring(colon + 1);

        boolean v2 = false;
        boolean pty = false;
        boolean raw = false;
        String term = null;
        for (String arg : args.split(",")) {
            if (arg.isEmpty()) continue;
            if (arg.equals("v2")) {
                v2 = true;
            } else if (arg.equals("pty")) {
                pty = true;
            } else if (arg.equals("raw")) {
                raw = true;
            } else if (arg.startsWith("TERM=")) {
                term = arg.substring("TERM=".length());
            }
            // Unknown args are ignored, like adbd.
        }

        ShellRunner runner = new ShellRunner();

        // Java-backed emulation for commands that need it; everything else
        // runs the real Android binary via sh -c.
        DeviceCommandHandlers.Result emulated = DeviceCommandHandlers.handle(command);
        if (emulated != null) {
            d(service, "emulated command exit=" + emulated.mExit, service);
            HostServices.writeOkay(out);
            if (v2) {
                byte[] data = emulated.mStdout.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
                int off = 0;
                while (off < data.length) {
                    int n = Math.min(ShellProtocol.MAX_PAYLOAD, data.length - off);
                    ShellProtocol.writePacket(out, ShellProtocol.ID_STDOUT, data, off, n);
                    off += n;
                }
                if (emulated.mStderr != null && !emulated.mStderr.isEmpty()) {
                    byte[] err = emulated.mStderr.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8);
                    ShellProtocol.writePacket(out, ShellProtocol.ID_STDERR, err,
                        0, err.length);
                }
                ShellProtocol.writeExit(out, emulated.mExit);
            } else {
                // Legacy: single merged stream, no exit status.
                out.write(emulated.mStdout.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (emulated.mStderr != null) {
                    out.write(emulated.mStderr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                out.flush();
            }
            // Legacy shell services: some ADB clients expect the stream to close
            // after output instead of waiting indefinitely. Emit CLOSE_STDIN (v2)
            // or simply close the socket by throwing NoQueryTailException.
            throw new NoQueryTailException();
        }

        boolean usePty = raw ? false : (pty || !v2);
        // An empty command means a login shell on the device.
        String effectiveCommand = command.isEmpty() ? "sh" : command;
        d(service, "executing command via sh -c (pty=" + usePty + " v2=" + v2 + " command=" + effectiveCommand + ")", service);

        HostServices.writeOkay(out);

        if (usePty) {
            ShellRunner.PtyEngine engine = runner.startPty(effectiveCommand, term, 0, 0);
            if (engine == null) {
                // PTY unavailable (libadbpty not loadable): fall back to pipes,
                // which is what adbd does when the pty provider fails.
                d(service, "pty unavailable, falling back to pipe", service);
                runPipeShell(runner, in, out, effectiveCommand, v2, term);
                throw new NoQueryTailException();
            }
            final InputStream fin = in;
            final ShellRunner.PtyEngine fengine = engine;
            final boolean fpty = pty;
            final boolean fv2 = v2;
            Thread stdinThread = new Thread(() -> pumpShellStdin(fin, fengine, fv2, fpty),
                "adb-shell-stdin");
            stdinThread.setDaemon(true);
            stdinThread.start();
            d(service, "pty shell started pid=" + fengine.mPid, service);
            engine.run(out, v2);
            engine.kill(); // if output EOF'd early, ensure the child is gone
            throw new NoQueryTailException();
        }
        runPipeShell(runner, in, out, effectiveCommand, v2, term);
        throw new NoQueryTailException();
    }

    private static void runPipeShell(ShellRunner runner, InputStream in, OutputStream out,
                                     String command, boolean v2, String term) throws IOException {
        ShellRunner.PipeEngine engine;
        try {
            engine = runner.startPipe(command);
        } catch (IOException e) {
            TermdebAdbBridge.logWarn(TermdebAdbBridge.LOG_TAG, "Pipe shell failed, falling back to PTY: " + e);
            runPtyShellFallback(runner, in, out, command, v2, term);
            return;
        }
        Thread stdinThread = new Thread(() -> pumpShellStdin(in, engine, v2, false),
            "adb-shell-stdin");
        stdinThread.setDaemon(true);
        stdinThread.start();
        engine.run(out, v2);
        engine.kill();
    }

    private static void runPtyShellFallback(ShellRunner runner, InputStream in, OutputStream out,
                                             String command, boolean v2, String term) {
        try {
            ShellRunner.PtyEngine engine = runner.startPty(command, term, 0, 0);
            if (engine == null) {
                sendShellError(out, "PTY unavailable and pipe failed", v2);
                return;
            }
            Thread stdinThread = new Thread(() -> pumpShellStdin(in, engine, v2, true),
                "adb-shell-stdin");
            stdinThread.setDaemon(true);
            stdinThread.start();
            engine.run(out, v2);
            engine.kill();
        } catch (IOException e) {
            sendShellError(out, "PTY fallback failed: " + e, v2);
        }
    }

    private static void sendShellError(OutputStream out, String msg, boolean v2) {
        try {
            byte[] data = msg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (v2) {
                ShellProtocol.writePacket(out, ShellProtocol.ID_STDERR, data, 0, data.length);
                ShellProtocol.writeExit(out, 1);
            } else {
                // Legacy shell is a raw stream: plain text, no exit status.
                out.write(data);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Read shell-protocol packets (or raw bytes for legacy) from the client
     * and forward stdin to the running engine until the client goes away.
     */
    private static void pumpShellStdin(InputStream in, Object engine, boolean v2,
                                       boolean ptyMode) {
        try {
            if (!v2) {
                // Legacy: raw bytes to the pty master.
                byte[] buf = new byte[8 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (ptyMode && engine instanceof ShellRunner.PtyEngine) {
                        if (((ShellRunner.PtyEngine) engine).writeStdin(buf, n) < 0) break;
                    }
                }
                return;
            }
            byte[] data = new byte[ShellProtocol.MAX_PAYLOAD];
            int[] len = new int[1];
            while (true) {
                int id = ShellProtocol.readPacket(in, data, len);
                if (id < 0) return; // client closed
                if (id == ShellProtocol.ID_STDIN) {
                    if (len[0] == 0) continue;
                    if (engine instanceof ShellRunner.PtyEngine) {
                        if (((ShellRunner.PtyEngine) engine).writeStdin(data, len[0]) < 0) {
                            return;
                        }
                    } else if (engine instanceof ShellRunner.PipeEngine) {
                        if (!((ShellRunner.PipeEngine) engine).writeStdin(data, len[0])) {
                            return;
                        }
                    }
                } else if (id == ShellProtocol.ID_WINDOW_SIZE_CHANGE) {
                    int[] rc = ShellProtocol.parseWindowSize(data, len[0]);
                    if (rc != null && engine instanceof ShellRunner.PtyEngine) {
                        ((ShellRunner.PtyEngine) engine).setWindowSize(rc[0], rc[1]);
                    }
                } else if (id == ShellProtocol.ID_CLOSE_STDIN) {
                    if (engine instanceof ShellRunner.PipeEngine) {
                        ((ShellRunner.PipeEngine) engine).closeStdin();
                    }
                    // PTYs cannot be half-closed; ignore, like adbd.
                } else if (id == ShellProtocol.ID_EXIT || id == ShellProtocol.ID_STDOUT
                    || id == ShellProtocol.ID_STDERR) {
                    // Device-bound ids from a client are ignored, like adbd.
                } else {
                    return; // invalid id: drop the connection
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Connection closed or protocol garbage: stop pumping stdin.
        }
    }

    // ---------- exec ----------

    /**
     * "exec:<command>": raw merged stdout+stderr, no PTY, no exit status.
     * Handles the streamed install path ("cmd package install-*") via
     * PackageManagerService; everything else executes for real.
     */
    private static boolean handleExec(InputStream in, OutputStream out, String command)
        throws IOException {
        d(command, "exec", command);
        HostServices.writeOkay(out);
        String trimmed = command.trim();
        if (trimmed.startsWith("cmd package ")) {
            PackageManagerService pkg = new PackageManagerService();
            pkg.handleCmdPackage(trimmed.substring("cmd ".length()), in, out);
            d(command, "cmd package done", command);
            out.flush();
            throw new NoQueryTailException();
        }
        DeviceCommandHandlers.Result emulated = DeviceCommandHandlers.handle(command);
        if (emulated != null) {
            d(command, "emulated exec exit=" + emulated.mExit, command);
            out.write((emulated.mStdout + (emulated.mStderr == null ? ""
                : emulated.mStderr)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            throw new NoQueryTailException();
        }
        // Real execution with merged stderr (exec: cannot separate streams).
        ShellRunner runner = new ShellRunner();
        ShellRunner.PipeEngine engine;
        try {
            engine = runner.startExec(command);
        } catch (IOException e) {
            TermdebAdbBridge.logWarn(TermdebAdbBridge.LOG_TAG, "Exec failed: " + e);
            String msg = "Exec failed: " + e.getMessage();
            out.write(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            throw new NoQueryTailException();
        }
        d(command, "executing via sh -c", command);
        // adb exec-in writes the payload to the socket after OKAY; without a
        // stdin pump the data is never read and the command sees an empty
        // input. Pump raw bytes, then half-close the child's stdin so input-
        // driven commands (cat, tar, ...) terminate like on a real device.
        final ShellRunner.PipeEngine fengine = engine;
        final InputStream fin = in;
        Thread stdinThread = new Thread(() -> {
            byte[] buf = new byte[8 * 1024];
            try {
                int n;
                while ((n = fin.read(buf)) > 0) {
                    if (!fengine.writeStdin(buf, n)) break;
                }
            } catch (IOException ignored) {
                // client went away
            }
            fengine.closeStdin();
        }, "adb-exec-stdin");
        stdinThread.setDaemon(true);
        stdinThread.start();
        engine.runRaw(out);
        engine.kill();
        throw new NoQueryTailException();
    }

    // ---------- reverse ----------

    /**
     * "reverse:forward:<remote>;<local>" etc. The device side listens on the
     * remote endpoint; incoming device connections are forwarded to the host
     * endpoint. Since bridge "device" and "host" are the same process, this
     * mirrors ForwardRegistry with swapped specs.
     */
    private static void handleReverse(OutputStream out, String spec) throws IOException {
        if (spec.startsWith("list-forward")) {
            HostServices.writeOkay(out);
            HostServices.writeMessage(out, ForwardRegistry.listForward(true));
            throw new NoQueryTailException();
        }
        if (spec.equals("killforward-all")) {
            ForwardRegistry.killForwardAll(true);
            // The client (commandline.cpp forward/reverse block) reads status
            // twice: adb_connect's embedded adb_status, then adb_status again
            // — 1st OKAY is connect, 2nd OKAY is status.
            HostServices.writeOkay(out);
            HostServices.writeOkay(out);
            throw new NoQueryTailException();
        }
        if (spec.startsWith("killforward")) {
            String local = spec.substring("killforward".length());
            if (local.startsWith(":")) local = local.substring(1);
            HostServices.writeOkay(out); // 1st OKAY: connect
            if (!ForwardRegistry.killForward(true, local)) {
                HostServices.writeFail(out, "listener '" + local + "' not found");
                throw new NoQueryTailException();
            }
            HostServices.writeOkay(out); // 2nd OKAY: status
            throw new NoQueryTailException();
        }
        if (spec.startsWith("forward")) {
            String rest = spec.substring("forward".length());
            if (rest.startsWith(":")) rest = rest.substring(1);
            boolean norebind = false;
            if (rest.startsWith("norebind:")) {
                norebind = true;
                rest = rest.substring("norebind:".length());
            }
            int semi = rest.indexOf(';');
            if (semi < 0) {
                HostServices.writeFail(out, "bad reverse: " + rest);
                throw new NoQueryTailException();
            }
            // The device listens on the endpoint before the ';' and relays to
            // the host endpoint after it (AOSP install_listener(pieces[0],
            // pieces[1]) on the device side).
            String deviceEndpoint = rest.substring(0, semi);
            String hostEndpoint = rest.substring(semi + 1);
            StringBuilder err = new StringBuilder();
            int resolvedTcpPort = ForwardRegistry.addForward(true, deviceEndpoint, hostEndpoint,
                norebind, err);
            if (resolvedTcpPort < 0) {
                // Device-side model: adbd's service dispatch sends the
                // activation OKAY before the handler runs, so a failure is
                // OKAY followed by the FAIL status.
                HostServices.writeOkay(out);
                HostServices.writeFail(out, resolvedTcpPort == ForwardRegistry.ERR_CANNOT_REBIND
                    ? "cannot rebind existing socket"
                    : "cannot bind listener: "
                        + (err.length() == 0 ? "unknown error" : err));
                throw new NoQueryTailException();
            }
            // 1st OKAY: activation (adb_connect's embedded adb_status); 2nd
            // OKAY: status (commandline.cpp's own adb_status call); then the
            // actually-bound port for tcp:0 reverse listeners, which the
            // client prints.
            HostServices.writeOkay(out);
            HostServices.writeOkay(out);
            if (resolvedTcpPort > 0) {
                HostServices.writeMessage(out, String.valueOf(resolvedTcpPort));
            }
            throw new NoQueryTailException();
        }
    }

    // ---------- transport connect ----------

    /**
     * tcp:PORT / local:... device service: connect and relay. The target is
     * resolved against the SELECTED transport: on the virtual device it is a
     * loopback socket in the TermDeb network namespace; on a remote transport
     * the service string is forwarded to the remote adbd verbatim, which
     * connects on the REMOTE device.
     */
    private static boolean handleTransportConnect(String serial, InputStream in, OutputStream out,
                                                  String spec) throws IOException {
        // A remote transport: forward the service string verbatim (the remote
        // adbd performs the connect on its own host).
        if (serial != null && !SERIAL.equals(serial)) {
            RemoteDevice d = AdbTransportManager.bySpec(serial);
            if (d != null && d.isOnline()) {
                return TransportSelection.remote(d).serve(null, in, out, spec);
            }
            HostServices.writeFail(out, "device '" + serial + "' not found");
            throw new NoQueryTailException();
        }
        HostServices.writeOkay(out);
        // The client will stream raw bytes both ways; relay through the same
        // plumbing forwards use. (Not exercised by the bundled client.)
        java.net.Socket relay = null;
        try {
            if (spec.startsWith("tcp:")) {
                int port = Integer.parseInt(spec.substring(4).trim());
                relay = new java.net.Socket();
                relay.connect(new java.net.InetSocketAddress(
                    java.net.InetAddress.getByName("127.0.0.1"), port), 5000);
            } else {
                throw new IOException("unsupported transport endpoint '" + spec + "'");
            }
        } catch (IOException e) {
            throw new NoQueryTailException();
        }
        java.net.Socket finalRelay = relay;
        Thread t = new Thread(() -> {
            try {
                ForwardRegistry.copy(in, finalRelay.getOutputStream());
            } catch (IOException ignored) {
            } finally {
                try {
                    finalRelay.close();
                } catch (IOException ignored) {
                }
            }
        }, "adb-tport-out");
        t.setDaemon(true);
        t.start();
        try {
            ForwardRegistry.copy(finalRelay.getInputStream(), out);
        } catch (IOException ignored) {
        }
        try {
            finalRelay.close();
        } catch (IOException ignored) {
        }
        throw new NoQueryTailException();
    }

    /** Application context for Java-backed command emulation. */
    static Context appContext() {
        return TermdebAdbBridge.appContext();
    }
}
