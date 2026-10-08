/*
 * TermDeb ADB bridge — transport selection helpers.
 *
 * The bridge hosts a virtual device (emulator-5554) plus any number of real
 * network transports (`adb connect host:port`). Selection follows AOSP
 * acquire_one_transport semantics: -s serial, -t transport id, -d (error if
 * multiple USB devices — here always the virtual one), -e (error if multiple
 * network devices), default = error when more than one device exists.
 */
package com.termux.app.adb;

import com.termux.app.adb.remote.AdbTransportManager;
import com.termux.app.adb.remote.RemoteDevice;
import com.termux.app.adb.remote.RemoteStreamService;

/** Resolve a serial/transport-id to a device service entry point. */
final class TransportSelection {

    private TransportSelection() {
    }

    /** What a selected transport can do. */
    interface Target {
        /** Run a device service on this transport. */
        boolean serve(java.net.Socket socket, java.io.InputStream in,
                      java.io.OutputStream out, String service) throws java.io.IOException;

        /** The transport id reported for the new-protocol tport switch. */
        long transportId();
    }

    /**
     * Resolve the target for a device service request.
     *
     * @param serial  wanted serial, or null for default/-d/-e selection
     * @param tid     wanted transport id string, or null
     * @param wantUsb true if the client passed -d (USB device)
     * @param wantTcp true if the client passed -e (network device)
     * @return a Target, or null if no device matched (caller writes the FAIL).
     */
    static Target select(String serial, String tid, boolean wantUsb, boolean wantTcp) {
        // -d: the only USB-class device is the bridge's own virtual device.
        if (wantUsb) {
            return virtual();
        }
        // -e: network transports only; fail if there are several, like AOSP
        // ("more than one device/emulator" wording is for the ambiguous
        // default case; -e has its own message).
        if (wantTcp) {
            java.util.List<RemoteDevice> remotes = AdbTransportManager.devices();
            java.util.List<RemoteDevice> online = new java.util.ArrayList<>();
            for (RemoteDevice d : remotes) {
                if (d.isOnline()) online.add(d);
            }
            if (online.size() > 1) {
                return ambiguous("more than one emulator/device");
            }
            if (online.size() == 1) {
                return remote(online.get(0));
            }
            return virtual();
        }
        // -t: transport id. Virtual device is 1; remote devices use their
        // registration order offset by 1000.
        if (tid != null) {
            long id;
            try {
                id = Long.parseLong(tid);
            } catch (NumberFormatException e) {
                return null;
            }
            if (id == 1) return virtual();
            java.util.List<RemoteDevice> remotes = AdbTransportManager.devices();
            for (RemoteDevice d : remotes) {
                if (d.isOnline() && transportIdOf(d) == id) {
                    return remote(d);
                }
            }
            return null;
        }
        // -s or default: exact serial match first.
        if (serial != null) {
            if (DeviceServices.SERIAL.equals(serial)) return virtual();
            RemoteDevice d = AdbTransportManager.bySpec(serial);
            if (d == null || !d.isOnline()) {
                d = com.termux.app.adb.wireless.WirelessTransportManager.bySpec(serial);
            }
            if (d != null && d.isOnline()) return remote(d);
            return null;
        }
        // Default selection: exactly one device overall, else AOSP's error.
        java.util.List<RemoteDevice> online = new java.util.ArrayList<>();
        for (RemoteDevice d : AdbTransportManager.devices()) {
            if (d.isOnline()) online.add(d);
        }
        int total = online.size() + 1; // + virtual
        if (total > 1) {
            return ambiguous("more than one device/emulator");
        }
        return virtual();
    }

    /** Remote transport-id (stable per spec within a session). */
    static long transportIdOf(RemoteDevice d) {
        // Deterministic per spec: hash of the spec string, forced >1000.
        long h = 1125899906842597L;
        for (int i = 0; i < d.getSpec().length(); i++) {
            h = 31 * h + d.getSpec().charAt(i);
        }
        long id = Math.abs(h % 1_000_000_007L);
        return id < 1000 ? id + 1000 : id;
    }

    /** The local virtual device. */
    static Target virtual() {
        return new Target() {
            @Override
            public boolean serve(java.net.Socket socket, java.io.InputStream in,
                                 java.io.OutputStream out, String service)
                throws java.io.IOException {
                return DeviceServices.handleDeviceService(socket, in, out, service);
            }

            @Override
            public long transportId() {
                return 1;
            }
        };
    }

    /** A real network transport. */
    static Target remote(RemoteDevice device) {
        return new Target() {
            @Override
            public boolean serve(java.net.Socket socket, java.io.InputStream in,
                                 java.io.OutputStream out, String service)
                throws java.io.IOException {
                RemoteStreamService.serve(device, service, in, out);
                return true;
            }

            @Override
            public long transportId() {
                return transportIdOf(device);
            }
        };
    }

    /** A target that always fails with AOSP's ambiguity error. */
    static Target ambiguous(String message) {
        return new Target() {
            @Override
            public boolean serve(java.net.Socket socket, java.io.InputStream in,
                                 java.io.OutputStream out, String service)
                throws java.io.IOException {
                HostServices.writeFail(out, message);
                throw new DeviceServices.NoQueryTailException();
            }

            @Override
            public long transportId() {
                return 0;
            }
        };
    }
}
