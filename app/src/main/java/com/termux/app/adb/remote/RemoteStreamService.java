/*
 * TermDeb ADB bridge — device services over a REAL remote transport.
 *
 * When the guest's `adb -s 192.168.x.x:5555 shell ...` targets a network
 * transport, the bridge acts as the smart-protocol server: it selects the
 * transport, writes the device service string ("shell:...", "sync:",
 * "tcp:...", "reverse:...") over an A_OPEN stream, and relays raw bytes
 * between the guest client socket and the remote stream — byte-for-byte the
 * same role the official adb server plays for its clients. All framing is the
 * remote adbd's problem; nothing is emulated.
 */
package com.termux.app.adb.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Serves guest client connections against a remote transport. */
public final class RemoteStreamService {

    private RemoteStreamService() {
    }

    /**
     * Send the service string after transport selection, then relay.
     *
     * Service-activation discipline matches the bridge's device services: the
     * OKAY is written to the guest client once the remote end accepts the
     * A_OPEN (i.e. the service really exists), and a refusal becomes a FAIL
     * with the remote's verdict. Nothing is fabricated: if the remote adbd
     * says "unauthorized" or "closed", that is what the guest sees.
     */
    public static void serve(RemoteDevice device, String service,
                             InputStream clientIn, OutputStream clientOut) throws IOException {
        RemoteDevice.RemoteStream stream = device.open(service);
        boolean accepted;
        try {
            accepted = stream.awaitAccepted(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stream.close();
            throw new IOException("interrupted while opening '" + service + "'", e);
        }
        if (!accepted) {
            IOException err = stream.getError();
            stream.close();
            com.termux.app.adb.HostServices.writeFail(clientOut,
                err != null ? "failed to connect to '" + service + "': " + err.getMessage()
                    : "device offline or service refused: " + service);
            if (err != null) throw err;
            throw new IOException("device offline or service refused: " + service);
        }
        com.termux.app.adb.HostServices.writeOkay(clientOut);
        try {
            relay(clientIn, clientOut, stream);
        } finally {
            stream.close();
        }
    }

    /** Bidirectional pump between the guest socket and the remote stream. */
    private static void relay(InputStream clientIn, OutputStream clientOut,
                              RemoteDevice.RemoteStream stream) throws IOException {
        Thread toRemote = new Thread(() -> {
            byte[] buf = new byte[32 * 1024];
            try {
                int n;
                while ((n = clientIn.read(buf)) > 0) {
                    stream.write(buf, 0, n);
                }
                // EOF from the client: half-close our side of the stream.
                stream.close();
            } catch (IOException ignored) {
                try {
                    stream.close();
                } catch (Exception ignored2) {
                }
            }
        }, "adb-rs-to-remote");
        toRemote.setDaemon(true);
        toRemote.start();

        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = stream.read(buf)) > 0) {
            clientOut.write(buf, 0, n);
            clientOut.flush();
        }
        // Interrupt the pump if the remote closed first.
        toRemote.interrupt();
    }
}
