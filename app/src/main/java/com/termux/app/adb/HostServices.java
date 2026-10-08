/*
 * TermDeb ADB bridge — host services.
 *
 * Implements the AOSP host services used by an adb client talking to this
 * bridge (verified against AOSP docs/dev/services.md and commandline.cpp):
 *
 *   host:version                     OKAY + hex4 ADB_SERVER_VERSION + tail
 *   host:features                    feature set the official client keys on
 *   host:devices[-l]                 live device list (virtual + network)
 *   host:track-devices               long-lived streaming device list
 *   host-serial:<serial>:<service>   serial-scoped host queries
 *   host:transport[-any][:<serial>]  transport selection, then the next
 *                                    service string on the same socket
 *   host-transport-id:<id>:<service> adb -t <id>
 *   host:forward[:norebind]:l;r      forward registry (OKAY / FAIL)
 *   host:list-forward / killforward[-all]
 *   host:kill                        server shutdown (with prompt restart)
 *   anything else                    device service dispatch (TransportSelection)
 *
 * Reply discipline (smart socket): every successful service activation sends
 * a bare "OKAY" first; query services then send a hex4 payload and the
 * trailing "0000" tail; activation failures send "FAIL" + hex4 reason. Device
 * services are raw streams after OKAY and must not get the tail.
 */
package com.termux.app.adb;

import com.termux.app.adb.remote.AdbTransportManager;
import com.termux.app.adb.remote.RemoteDevice;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** AOSP-compatible host services layer for the TermDeb ADB bridge. */
public final class HostServices {

    static final String SERIAL = "emulator-5554";

    /** Wire protocol version this bridge reports (ADB_SERVER_VERSION 41). */
    static final int ADB_SERVER_VERSION = 41;

    /**
     * Features advertised to the client. Matches AOSP adb's feature string
     * minus capabilities the bridge does not implement (sendrecv_v2, abb,
     * abb_exec, incremental, brotli/zstd/deflate compression), so official
     * clients fall back to the v1 sync path and streamed install via `cmd`.
     */
    private static final String FEATURES = String.join(",",
        "shell_v2", "cmd", "stat_v2", "ls_v2", "fixed_push_mkdir", "apex");

    static final byte[] OKAY = {'O', 'K', 'A', 'Y'};
    static final byte[] FAIL = {'F', 'A', 'I', 'L'};

    /** Set when host:kill unwinds the listener; suppresses the error log. */
    static final AtomicBoolean SHUTDOWN = new AtomicBoolean(false);

    private static volatile boolean DEBUG = false;

    static void setDebug(boolean on) {
        DEBUG = on;
    }

    static boolean isDebugEnabled() {
        return DEBUG;
    }

    private static void d(String svc, String msg) {
        if (DEBUG) TermdebAdbBridge.logDebug("HostServices", svc + ": " + msg);
    }

    private final DeviceServices mDeviceServices = new DeviceServices();
    private final ForwardRegistry mForwards = new ForwardRegistry();

    static boolean isShutdownCaused() {
        return SHUTDOWN.get();
    }

    /** Read a 4-hex-digit length + payload smart-protocol message. */
    static String readMessage(InputStream in) throws IOException {
        byte[] lenBuf = new byte[4];
        readFully(in, lenBuf, 0, 4);
        int len = parseHex4(lenBuf);
        byte[] buf = new byte[len];
        readFully(in, buf, 0, len);
        return new String(buf, StandardCharsets.UTF_8);
    }

    /**
     * Read a device service string sent after transport selection.
     *
     * AOSP commandline.cpp always sends this string through SendProtocolString,
     * i.e. hex4 length-prefixed (adb_sendbuf.cpp: "hex4 length + payload"). The
     * legacy 4-byte-read heuristic could desync whenever the client's write was
     * split across TCP segments, so the length prefix is authoritative here.
     */
    static String readDeviceServiceString(InputStream in) throws IOException {
        return readMessage(in);
    }

    /**
     * Write the raw 8-byte little-endian transport id the new (non-legacy)
     * tport:* transport-switch reply requires. AOSP adb.cpp
     * handle_host_request: after OKAY, WriteFdExactly(reply_fd, &t->id,
     * sizeof(t->id)) — 8 bytes, native-endian. The official client reads these
     * bytes unconditionally after the switch OKAY (switch_socket_transport in
     * adb_client.cpp); omitting them makes every device service hang forever.
     */
    private static void writeTransportId(OutputStream out, long id) throws IOException {
        out.write(new byte[] {
            (byte) (id & 0xff),
            (byte) ((id >> 8) & 0xff),
            (byte) ((id >> 16) & 0xff),
            (byte) ((id >> 24) & 0xff),
            (byte) ((id >> 32) & 0xff),
            (byte) ((id >> 40) & 0xff),
            (byte) ((id >> 48) & 0xff),
            (byte) ((id >> 56) & 0xff),
        });
        out.flush();
    }

    /**
     * Complete the new-protocol transport switch: OKAY, then the raw 8-byte
     * transport id, then read the hex4-framed next service string and dispatch
     * it. The client sends ANY service here (adb_client.cpp _adb_connect),
     * including host services (e.g. "host:forward:..." for `adb forward`, since
     * commandline.cpp force-switches for forward/reverse), so host services
     * recurse back into handleService while device services are dispatched
     * through the selected target (they own the raw stream from here on).
     */
    private void finishTransportSwitch(Socket socket, InputStream in, OutputStream out,
                                       TransportSelection.Target target) throws IOException {
        writeOkay(out);
        writeTransportId(out, target.transportId());
        String next = readDeviceServiceString(in);
        d("host service", "transport switch next: " + next);
        if (next.startsWith("host:") || next.startsWith("host-serial:")
            || next.startsWith("host-transport-id:")) {
            handleService(socket, in, out, next);
        } else {
            dispatchDeviceServiceOrFail(socket, in, out, next, target);
        }
        throw new DeviceServices.NoQueryTailException();
    }

    /**
     * Dispatch a device service on the selected transport; if the transport
     * cannot serve it, answer FAIL (no tail — the raw-stream context after a
     * transport switch) so the client reports "unknown service" instead of
     * seeing a silent close.
     */
    private void dispatchDeviceServiceOrFail(Socket socket, InputStream in, OutputStream out,
                                             String service, TransportSelection.Target target)
        throws IOException {
        if (target == null) {
            writeFail(out, "device offline or not found");
            throw new DeviceServices.NoQueryTailException();
        }
        if (!target.serve(socket, in, out, service)) {
            d("host service", "unknown device service: " + service);
            writeFail(out, "unknown service " + service);
        }
    }

    /**
     * wait-for-<transport>-<state>: satisfied when at least one device is
     * online. The virtual device is always present; with a default selection
     * the wait is immediate (AOSP waits for the selected transport's state).
     */
    private static boolean handleWaitForDevice(OutputStream out, String rest) throws IOException {
        if (!rest.startsWith("wait-for-")) return false;
        d("host service", "wait-for satisfied: " + rest);
        writeOkay(out);
        writeOkay(out);
        return true;
    }

    /** Write a 4-hex-digit length + payload smart-protocol message. */
    static void writeMessage(OutputStream out, String payload) throws IOException {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        if (data.length > 0xFFFF) throw new IOException("payload too large");
        out.write(String.format("%04x", data.length).getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }

    /** Write the bare OKAY activation marker (no framing). */
    public static void writeOkay(OutputStream out) throws IOException {
        out.write(OKAY);
        out.flush();
    }

    /** Write a FAIL activation marker with a hex4-framed reason. */
    public static void writeFail(OutputStream out, String reason) throws IOException {
        out.write(FAIL);
        writeMessage(out, reason);
    }

    /** Trailing "0000" that closes a query-style service reply. */
    static void writeQueryTail(OutputStream out) throws IOException {
        out.write('0');
        out.write('0');
        out.write('0');
        out.write('0');
        out.flush();
    }

    /** OKAY + payload in one call for query-style services. */
    private static void writeOkayPayload(OutputStream out, String payload) throws IOException {
        writeOkay(out);
        writeMessage(out, payload);
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len)
        throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(buf, off + got, len - got);
            if (n < 0) throw new EOFException("EOF after " + got + "/" + len + " bytes");
            got += n;
        }
    }

    private static int parseHex4(byte[] buf) throws IOException {
        int len = 0;
        for (int i = 0; i < 4; i++) {
            int v = Character.digit((char) (buf[i] & 0xff), 16);
            if (v < 0) throw new IOException("bad hex4 length prefix");
            len = (len << 4) | v;
        }
        return len;
    }

    /**
     * Smart-protocol connection lifecycle. Handles the service activation and
     * the trailing "0000" for query-style services; device services manage
     * their own raw streams and signal "no tail" via NoQueryTailException.
     */
    boolean handleSmartProtocol(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String service = readMessage(in);
            d("smart proto", "service received: " + service);
            boolean handled = handleService(socket, in, out, service);
            if (handled) {
                // Device services already signalled NoQueryTailException;
                // only query-style services reach here.
                d("host service", "writing query tail for: " + service);
                writeQueryTail(out);
            }
            return handled;
        } catch (DeviceServices.NoQueryTailException ignored) {
            // Raw device service: the "0000" tail must not be appended.
            d("smart proto", "device service finished (no tail)");
            return true;
        } catch (ShutdownException e) {
            // Reply already completed inside handleService.
            d("smart proto", "shutdown");
            return true;
        } catch (Exception e) {
            d("smart proto", "error: " + e);
            return false;
        }
    }

    /** Handle a service string; returns false if the service is unknown. */
    boolean handleService(Socket socket, InputStream in, OutputStream out, String service)
        throws IOException {
        String serial = null;
        String rest;

        // host-transport-id:<id>:<service> — adb -t <id> host queries and
        // device services (format_host_command in adb_client.cpp).
        if (service.startsWith("host-transport-id:")) {
            String body = service.substring("host-transport-id:".length());
            int idx = body.indexOf(':');
            if (idx < 0) return false;
            String tid = body.substring(0, idx);
            String inner = body.substring(idx + 1);
            d("host service", "host-transport-id tid=" + tid + " inner=" + inner);
            TransportSelection.Target target = TransportSelection.select(null, tid, false, false);
            if (target == null) {
                // AOSP acquire_one_transport: "no device with transport id '%llu'".
                writeFail(out, "no device with transport id '" + tid + "'");
                return true;
            }
            if (isDeviceService(inner)) {
                dispatchDeviceServiceOrFail(socket, in, out, inner, target);
                throw new DeviceServices.NoQueryTailException();
            }
            return handleService(socket, in, out, "host:" + inner);
        }

        // host-serial:<serial>:<device service> delegates immediately.
        if (service.startsWith("host-serial:")) {
            int prefixLen = "host-serial:".length();
            int idx = service.indexOf(':', prefixLen);
            if (idx < 0) return false;
            serial = service.substring(prefixLen, idx);
            rest = service.substring(idx + 1);
            if (isDeviceService(rest)) {
                d("host service", "host-serial device service rest=" + rest + " serial=" + serial);
                TransportSelection.Target target = TransportSelection.select(serial, null, false, false);
                if (target == null) {
                    writeFail(out, "device '" + serial + "' not found");
                    return true;
                }
                dispatchDeviceServiceOrFail(socket, in, out, rest, target);
                throw new DeviceServices.NoQueryTailException();
            }
            // Otherwise, fall through to host service handling with the parsed serial.
        } else if (service.startsWith("host-usb:") || service.startsWith("host-local:")) {
            int prefixLen = service.startsWith("host-usb:") ? "host-usb:".length() : "host-local:".length();
            serial = null;
            rest = service.substring(prefixLen);
        } else if (service.startsWith("host:")) {
            rest = service.substring("host:".length());
        } else {
            // Direct device service (client already selected the transport).
            dispatchDeviceServiceOrFail(socket, in, out, service, TransportSelection.select(null, null, false, false));
            return true;
        }

        // Transport selection (AOSP adb.cpp handle_host_request, "tport:" /
        // "transport" prefix block):
        //
        // - New protocol ("tport:..."): reply OKAY followed by the RAW 8-byte
        //   transport id (WriteFdExactly(reply_fd, &t->id, sizeof(t->id)));
        //   the official client reads those 8 bytes unconditionally.
        // - Legacy protocol ("transport..."): reply OKAY only; nothing follows.
        //
        // In both cases the client then sends the device service string on the
        // same socket, hex4-framed.
        if (rest.startsWith("tport:")) {
            String spec = rest.substring("tport:".length());
            d("host service", "tport switch spec=" + spec);
            if (spec.startsWith("serial:")) {
                String wanted = spec.substring("serial:".length());
                TransportSelection.Target t = TransportSelection.select(wanted, null, false, false);
                if (t == null) {
                    writeFail(out, "device '" + wanted + "' not found");
                    return true;
                }
                finishTransportSwitch(socket, in, out, t);
            }
            if (spec.equals("usb")) {
                finishTransportSwitch(socket, in, out, TransportSelection.select(null, null, true, false));
            }
            if (spec.equals("local")) {
                finishTransportSwitch(socket, in, out, TransportSelection.select(null, null, false, true));
            }
            if (spec.equals("any")) {
                TransportSelection.Target t = TransportSelection.select(null, null, false, false);
                if (t == null) {
                    writeFail(out, "more than one device/emulator");
                    return true;
                }
                finishTransportSwitch(socket, in, out, t);
            }
            // tport:<serial> (selection by id is unimplemented upstream; the
            // client only ever sends any/usb/local/serial:).
            TransportSelection.Target t = TransportSelection.select(spec, null, false, false);
            if (t != null) {
                finishTransportSwitch(socket, in, out, t);
            }
            writeFail(out, "unknown transport type '" + spec + "'");
            return true;
        }
        if (rest.equals("transport-any") || rest.equals("transport-usb")
            || rest.equals("transport-local")) {
            d("host service", "legacy transport switch: " + rest);
            boolean wantUsb = rest.equals("transport-usb");
            boolean wantTcp = rest.equals("transport-local");
            TransportSelection.Target t = TransportSelection.select(null, null, wantUsb, wantTcp);
            writeOkay(out);
            String next = readDeviceServiceString(in);
            d("host service", "legacy transport next: " + next);
            dispatchDeviceServiceOrFail(socket, in, out, next, t);
            throw new DeviceServices.NoQueryTailException();
        }
        if (rest.startsWith("transport:")) {
            String wanted = rest.substring("transport:".length());
            d("host service", "legacy transport serial switch: " + wanted);
            TransportSelection.Target t = TransportSelection.select(wanted, null, false, false);
            if (t != null) {
                writeOkay(out);
                String next = readDeviceServiceString(in);
                dispatchDeviceServiceOrFail(socket, in, out, next, t);
                throw new DeviceServices.NoQueryTailException();
            }
            writeFail(out, "device '" + wanted + "' not found");
            return true;
        }
        if (rest.startsWith("transport-id:")) {
            String wanted = rest.substring("transport-id:".length());
            d("host service", "transport-id switch: " + wanted);
            TransportSelection.Target t = TransportSelection.select(null, wanted, false, false);
            if (t == null) {
                writeFail(out, "no device with transport id '" + wanted + "'");
                return true;
            }
            writeOkay(out);
            String next = readDeviceServiceString(in);
            dispatchDeviceServiceOrFail(socket, in, out, next, t);
            throw new DeviceServices.NoQueryTailException();
        }
        switch (rest) {
            case "version":
                writeOkayPayload(out, String.format("%04x", ADB_SERVER_VERSION));
                return true;
            case "features":
            case "host-features":
                writeOkayPayload(out, FEATURES);
                return true;
            case "devices":
            case "devices-l":
                writeOkayPayload(out, DeviceServices.devicesList(rest.endsWith("-l")));
                return true;
            case "track-devices":
                writeOkay(out);
                handleTrackDevices(in, out);
                return true;
            case "kill":
                writeOkayPayload(out, "successfully kicked");
                // Bring the server down; TermdebAdbBridge restarts it shortly,
                // matching how a real `adb kill-server` is followed by a new
                // daemon on the next client command.
                SHUTDOWN.set(true);
                TermdebAdbBridge.requestServerRestart();
                throw new ShutdownException();
            case "reconnect":
            case "reconnect-offline":
                writeOkayPayload(out, "done");
                return true;
            case "get-state": {
                String state = DeviceServices.stateForSerial(serial);
                writeOkayPayload(out, state);
                return true;
            }
            case "get-serialno":
                writeOkayPayload(out, serial != null ? serial : DeviceServices.SERIAL);
                return true;
            case "get-devpath":
                writeOkayPayload(out, serial != null ? serial : DeviceServices.SERIAL);
                return true;
            case "get-transport-id": {
                long id = 1;
                if (serial != null && !DeviceServices.SERIAL.equals(serial)) {
                    RemoteDevice d = AdbTransportManager.bySpec(serial);
                    if (d == null || !d.isOnline()) {
                        writeFail(out, "device '" + serial + "' not found");
                        return true;
                    }
                    id = TransportSelection.transportIdOf(d);
                }
                writeOkayPayload(out, String.valueOf(id));
                return true;
            }
            case "get-product":
                writeOkayPayload(out, "termdeb");
                return true;
            case "wait-for-device":
            case "wait-for-any-device":
            case "wait-for-usb-device":
            case "wait-for-local-device":
            case "wait-for-disconnect":
                // The virtual device is always present; the wait is
                // immediately satisfied. adb_command() reads one final
                // adb_status() after activation, so TWO bare OKAYs are
                // required (see handleWaitForDevice).
                handleWaitForDevice(out, rest);
                return true;
            case "list-forward":
                writeOkayPayload(out, mForwards.listForward(false));
                return true;
            case "killforward-all":
                // AOSP handle_forward_request: "1st OKAY is connect, 2nd OKAY
                // is status" for the host-side forward registry.
                mForwards.killForwardAll(false);
                writeOkay(out);
                writeOkay(out);
                return true;
            default:
                break;
        }
        if (rest.startsWith("killforward:")) {
            String local = rest.substring("killforward:".length());
            if (local.startsWith("norebind:")) local = local.substring("norebind:".length());
            // AOSP handle_forward_request on the host sends the two OKAYs only
            // on success; a missing listener is a single FAIL whose text is
            // "listener '<name>' not found" (INSTALL_STATUS_LISTENER_NOT_FOUND).
            if (!mForwards.killForward(false, local)) {
                writeFail(out, "listener '" + local + "' not found");
                return true;
            }
            writeOkay(out);
            writeOkay(out);
            return true;
        }
        if (rest.startsWith("forward:")) {
            String spec = rest.substring("forward:".length());
            boolean norebind = false;
            if (spec.startsWith("norebind:")) {
                norebind = true;
                spec = spec.substring("norebind:".length());
            }
            int semi = spec.indexOf(';');
            if (semi < 0 || spec.indexOf(';', semi + 1) >= 0
                || spec.substring(0, semi).isEmpty() || spec.substring(semi + 1).isEmpty()
                || spec.charAt(semi + 1) == '*') {
                writeFail(out, "bad forward: " + spec);
                return true;
            }
            String local = spec.substring(0, semi);
            String remote = spec.substring(semi + 1);
            StringBuilder err = new StringBuilder();
            int resolvedTcpPort = mForwards.addForward(false, local, remote, norebind, err, serial);
            if (resolvedTcpPort < 0) {
                writeFail(out, resolvedTcpPort == ForwardRegistry.ERR_CANNOT_REBIND
                    ? "cannot rebind existing socket"
                    : "cannot bind listener: "
                        + (err.length() == 0 ? "unknown error" : err));
                return true;
            }
            // AOSP handle_forward_request (host side): 1st OKAY is connect,
            // 2nd OKAY is status; then, if a TCP port was resolved (tcp:0),
            // the actual port number as a hex4-framed string. Nothing else —
            // the client stops reading after the optional port string, so no
            // query tail may follow (enforced by returning true without
            // payload; handleSmartProtocol appends the "0000" tail which the
            // client treats as an orderly shutdown).
            writeOkay(out);
            writeOkay(out);
            if (resolvedTcpPort > 0) {
                writeMessage(out, String.valueOf(resolvedTcpPort));
            }
            return true;
        }
        if (rest.startsWith("pair:")) {
            // `adb pair <addr>`: REAL Android 11+ Wireless Debugging pairing
            // (SPAKE2 over TLS 1.3) executed by the app. Protocol shape:
            //   1. activation reply (OKAY, or FAIL when the address is bad)
            //   2. the client then sends a hex4-framed 6-digit pairing code
            //      on the SAME socket — read here, kept only in this frame,
            //      never stored or logged (the client prompts on its stderr
            //      so the code is typed, not passed as an argument)
            //   3. the pairing verdict, hex4-framed, then the socket closes.
            // Not a query service: no trailing 0000 — unwound like device
            // services via NoQueryTailException after the verdict is sent.
            String addr = rest.substring("pair:".length()).trim();
            String host = addr;
            int port = 0;
            int colon = addr.lastIndexOf(':');
            if (colon > 0) {
                host = addr.substring(0, colon);
                try {
                    port = Integer.parseInt(addr.substring(colon + 1));
                } catch (NumberFormatException e) {
                    port = -1;
                }
            }
            if (host.isEmpty() || port <= 0 || port > 65535) {
                writeFail(out, "invalid pairing address '" + addr + "'");
                throw new DeviceServices.NoQueryTailException();
            }
            writeOkay(out);
            // Read the 6-digit code the client sends after the prompt.
            byte[] codeBuf = new byte[6];
            try {
                byte[] lenBuf = new byte[4];
                readFully(in, lenBuf, 0, lenBuf.length);
                int codeLen = parseHex4(lenBuf);
                if (codeLen != 6) {
                    writeFail(out, "bad pairing code length");
                    throw new DeviceServices.NoQueryTailException();
                }
                readFully(in, codeBuf, 0, codeBuf.length);
                String code = new String(codeBuf, StandardCharsets.US_ASCII);
                String verdict = com.termux.app.adb.wireless.WirelessTransportManager
                    .pair(host, port, code);
                // Success is exactly "Successfully paired ..."; everything
                // else is an honest failure message from the manager.
                writeMessage(out, verdict);
            } catch (DeviceServices.NoQueryTailException e) {
                throw e;
            } catch (IOException e) {
                d("host service", "pair: client left before verdict: " + e);
            }
            throw new DeviceServices.NoQueryTailException();
        }
        if (rest.startsWith("connect:")) {
            // `adb connect <addr>`: a REAL network transport attempt against
            // the given adbd. Succeeds only when the remote speaks ADB.
            String addr = rest.substring("connect:".length());
            String host = addr;
            int port = 5555;
            int colon = addr.lastIndexOf(':');
            if (colon > 0) {
                host = addr.substring(0, colon);
                try {
                    port = Integer.parseInt(addr.substring(colon + 1));
                } catch (NumberFormatException e) {
                    writeOkayPayload(out, "cannot connect to " + addr + ": bad port");
                    return true;
                }
            }
            String msg = AdbTransportManager.connect(host, port);
            // AOSP connect_service prefers the secure (TLS) transport for
            // endpoints it already knows are Wireless Debugging ports. If the
            // plain attempt failed and this host is a paired wireless device,
            // try the secure path before reporting failure.
            if (!msg.startsWith("connected") && !msg.startsWith("already connected")) {
                String wireless = com.termux.app.adb.wireless.WirelessTransportManager
                    .connectIfPaired(host, port);
                if (wireless != null) msg = wireless;
            }
            writeOkayPayload(out, msg);
            return true;
        }
        if (rest.startsWith("disconnect:")) {
            // `adb disconnect [<addr>]`: drop matching live transports.
            String addr = rest.substring("disconnect:".length()).trim();
            if (addr.isEmpty()) {
                writeOkayPayload(out, com.termux.app.adb.wireless.WirelessTransportManager.disconnect(null, null)
                    + AdbTransportManager.disconnect(null, null));
                return true;
            }
            String host = addr;
            Integer port = null;
            int colon = addr.lastIndexOf(':');
            if (colon > 0) {
                host = addr.substring(0, colon);
                try {
                    port = Integer.parseInt(addr.substring(colon + 1));
                } catch (NumberFormatException e) {
                    writeOkayPayload(out, "error: bad port in '" + addr + "'\n");
                    return true;
                }
            } else {
                port = 5555; // AOSP disconnect defaults to :5555 when omitted
            }
            // Match against both registries: legacy TCP first, then the
            // Wireless Debugging TLS transports (serials are host:port for
            // both, so only one registry can hold the spec).
            String legacyMsg = AdbTransportManager.disconnect(host, port);
            if (legacyMsg.startsWith("error:")) {
                writeOkayPayload(out, com.termux.app.adb.wireless.WirelessTransportManager.disconnect(host, port));
                return true;
            }
            writeOkayPayload(out, legacyMsg);
            return true;
        }
        if (serial != null) {
            // Any other serial-scoped query on a device we know.
            d("host service", "unhandled serial-scoped: " + rest + " serial=" + serial);
            writeFail(out, "device '" + serial + "' not found");
            return true;
        }
        // Unknown host service: a real server answers "unknown service" so the
        // client reports an error instead of hanging or printing garbage.
        d("host service", "unknown service: " + service);
        writeFail(out, "unknown service " + rest);
        return true;
    }

    /**
     * Returns true if the given service string is a device service (shell/exec/sync/
     * reverse/track-jdwp/transport-connect) that should be dispatched as a
     * device service rather than handled as a host query.
     */
    private static boolean isDeviceService(String service) {
        return service.equals("sync:")
            || service.startsWith("shell")
            || service.startsWith("exec:")
            || service.startsWith("reverse:")
            || service.equals("track-jdwp")
            || service.startsWith("tcp:")
            || service.startsWith("local:")
            || service.startsWith("localabstract:");
    }

    /** Long-lived host:track-devices stream; pushes on device-list changes. */
    private void handleTrackDevices(InputStream in, OutputStream out) throws IOException {
        writeMessage(out, DeviceServices.devicesList(false));
        // Real adb pushes updates on device-list changes; poll the registry so
        // connects/disconnects reach `adb track-devices` and `adb wait-for`-
        // style consumers honestly. Client departure is detected both by
        // reads (EOF) and by writes (broken pipe).
        String last = DeviceServices.devicesList(false);
        byte[] probe = new byte[256];
        try {
            while (true) {
                while (in.available() > 0) {
                    if (in.read(probe) < 0) {
                        d("host service", "track-devices client closed");
                        return;
                    }
                }
                String now = DeviceServices.devicesList(false);
                if (!now.equals(last)) {
                    writeMessage(out, now);
                    last = now;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } catch (IOException e) {
            d("host service", "track-devices client closed");
        }
    }

    /** Thrown to unwind the connection when host:kill shuts the server down. */
    static final class ShutdownException extends RuntimeException {
    }
}
