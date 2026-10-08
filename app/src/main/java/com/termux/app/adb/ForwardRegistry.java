package com.termux.app.adb;

import android.net.LocalServerSocket;
import android.net.LocalSocketAddress;
import android.net.LocalSocket;

import com.termux.app.adb.remote.AdbTransportManager;
import com.termux.app.adb.remote.RemoteDevice;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Forward/reverse registry for the bridge.
 *
 * `adb forward` listeners run inside the bridge server (the "host" role):
 * guest processes connect to the local endpoint and are bridged to the remote
 * device endpoint. `adb reverse` listeners run on the "device" side (this
 * server, which IS the device process): the device listens and connects back
 * to the host endpoint when a device-side connection arrives.
 *
 * Guest and host share one loopback and network namespace (proot does not
 * namespace the network), so both directions reduce to listener + connect with
 * swapped roles. Supported endpoints: tcp:PORT, localabstract:NAME (real
 * AF_UNIX abstract sockets via android.net.LocalServerSocket — visible from
 * the guest because the abstract namespace is shared), and local:PATH (a
 * filesystem socket inside the app sandbox). jdwp: is refused with the same
 * error a real device gives a caller with nothing to connect to.
 */
final class ForwardRegistry {

    private static final String LOG_TAG = TermdebAdbBridge.LOG_TAG;

    private static final Object sLock = new Object();
    private static final List<Forward> sForwards = new ArrayList<>();

    /** The listener could not be bound (a real bind error; see the error text). */
    static final int ERR_CANNOT_BIND = -1;

    /** A listener for the same local endpoint exists and `norebind` was set. */
    static final int ERR_CANNOT_REBIND = -2;

    ForwardRegistry() {
    }

    // ---------- registry (host:forward / reverse:forward semantics) ----------

    /**
     * Register a listener for local;remote.
     *
     * Mirrors AOSP install_listener: the forward and reverse directions have
     * separate namespaces (on a real device the forward listeners live on the
     * host and the reverse listeners on the device), an existing listener for
     * the same local endpoint is *replaced* unless `norebind` is set, and a
     * `tcp:0` request is stored under the port it actually bound so that
     * list-forward and later killforward/rebind requests address it by name.
     *
     * @param reverse   true for a device-side (`reverse:`) listener.
     * @param local     the endpoint this side listens on.
     * @param remote    the endpoint connections are relayed to.
     * @param norebind  refuse to replace an existing listener.
     * @param errorOut  receives the human-readable failure text when one occurs.
     * @return the resolved TCP port (0 for non-tcp endpoints, or when the caller
     *         requested a specific port), or ERR_CANNOT_BIND / ERR_CANNOT_REBIND.
     */
    static int addForward(boolean reverse, String local, String remote, boolean norebind,
                          StringBuilder errorOut) {
        return addForward(reverse, local, remote, norebind, errorOut, null);
    }

    /**
     * Register a listener for local;remote, targeting the device selected by
     * {@code serial} (null = the bridge's virtual device). For a network
     * transport the remote endpoint is opened through the transport, so the
     * connection happens on the REMOTE device — exactly what a workstation
     * `adb -s <serial> forward` does.
     */
    static int addForward(boolean reverse, String local, String remote, boolean norebind,
                          StringBuilder errorOut, String serial) {
        synchronized (sLock) {
            for (int i = 0; i < sForwards.size(); i++) {
                Forward f = sForwards.get(i);
                if (f.reverse != reverse || !f.localSpec.equals(local)) continue;
                if (norebind) {
                    if (errorOut != null) errorOut.append("cannot rebind existing socket");
                    return ERR_CANNOT_REBIND;
                }
                // AOSP install_listener repurposes the existing listener: the
                // bound socket is kept and only its connect_to target changes.
                // Closing and re-binding instead would race with the accept
                // thread's own close and fail with EADDRINUSE. A repurpose
                // resolves no port either (AOSP leaves resolved_tcp_port 0), so
                // no port string is sent back for it.
                f.repoint(remote, serial);
                return 0;
            }
            Forward f;
            try {
                f = new Forward(reverse, local, remote, serial);
            } catch (IOException e) {
                TermdebAdbBridge.logWarn(LOG_TAG,
                    "forward " + local + " failed: " + e.getMessage());
                if (errorOut != null) {
                    errorOut.append(e.getMessage() == null ? "bind failed" : e.getMessage());
                }
                return ERR_CANNOT_BIND;
            }
            sForwards.add(f);
            f.start();
            return f.resolvedTcpPort;
        }
    }

    /**
     * Remove the listener whose local endpoint matches, within one direction.
     * AOSP remove_listener addresses listeners by their canonical local name.
     */
    static boolean killForward(boolean reverse, String localSpec) {
        synchronized (sLock) {
            for (int i = 0; i < sForwards.size(); i++) {
                Forward f = sForwards.get(i);
                if (f.reverse == reverse && f.localSpec.equals(localSpec)) {
                    f.stop();
                    sForwards.remove(i);
                    return true;
                }
            }
            return false;
        }
    }

    /** remove_all_listeners for one direction (host forwards or device reverses). */
    static void killForwardAll(boolean reverse) {
        synchronized (sLock) {
            for (int i = sForwards.size() - 1; i >= 0; i--) {
                if (sForwards.get(i).reverse == reverse) {
                    sForwards.get(i).stop();
                    sForwards.remove(i);
                }
            }
        }
    }

    /**
     * list-forward payload, one direction at a time.
     *
     * AOSP format_listeners: "<serial> <local> <remote>\n", with "(reverse)"
     * standing in for the serial of device-side (reverse) listeners.
     */
    static String listForward(boolean reverse) {
        StringBuilder sb = new StringBuilder();
        synchronized (sLock) {
            for (Forward f : sForwards) {
                if (f.reverse != reverse) continue;
                sb.append(reverse ? "(reverse)" : HostServices.SERIAL)
                    .append(' ')
                    .append(f.localSpec)
                    .append(' ')
                    .append(f.mRemoteSpec)
                    .append('\n');
            }
        }
        return sb.toString();
    }

    /** Close every listener (server shutdown). */
    static void killAllListeners() {
        synchronized (sLock) {
            for (Forward f : sForwards) f.stop();
            sForwards.clear();
        }
    }

    // ---------- one forward ----------

    private static final class Forward {
        /** true = device-side reverse listener, false = host-side forward listener. */
        final boolean reverse;
        /** Canonical local name: a bound tcp:0 listener is stored as tcp:<port>. */
        final String localSpec;
        /** Relay target; replaced in place by a rebind (AOSP install_listener). */
        private volatile String mRemoteSpec;
        /** Device the remote endpoint lives on; null = virtual device. */
        private volatile String mSerial;
        /** Port actually bound for a tcp:0 listener; 0 otherwise. */
        final int resolvedTcpPort;
        private final Listener mListener;
        private Thread mThread;
        private volatile boolean mRunning;

        Forward(boolean reverse, String localSpec, String remoteSpec, String serial)
            throws IOException {
            this.reverse = reverse;
            this.mRemoteSpec = remoteSpec;
            this.mSerial = serial;
            ListenerAndPort lp = openListener(localSpec);
            this.mListener = lp.listener;
            this.resolvedTcpPort = lp.port;
            // AOSP install_listener renames a tcp:0 request to the port it
            // actually bound; keep that canonical name for listing/matching.
            this.localSpec = lp.port != 0 ? "tcp:" + lp.port : localSpec;
        }

        /** Point this listener at a new remote endpoint, keeping its socket. */
        void repoint(String remoteSpec, String serial) {
            mRemoteSpec = remoteSpec;
            mSerial = serial;
        }

        void start() {
            mRunning = true;
            mThread = new Thread(this::acceptLoop, "adb-fwd-" + localSpec);
            mThread.setDaemon(true);
            mThread.start();
        }

        void stop() {
            mRunning = false;
            try { mListener.close(); } catch (IOException ignored) {}
        }

        private void acceptLoop() {
            while (mRunning) {
                try {
                    pump(mListener.accept());
                } catch (IOException e) {
                    if (mRunning) TermdebAdbBridge.logDebug(LOG_TAG,
                        "forward accept ended: " + e.getMessage());
                    break;
                }
            }
        }

        /** Accept on a tcp or local listener; yields a bidirectional handle. */
        private void pump(final Connection client) {
            Thread t = new Thread(() -> {
                Connection remote = null;
                try {
                    remote = connectRemote(mRemoteSpec, mSerial);
                } catch (IOException e) {
                    try { client.close(); } catch (IOException ignored) {}
                    return;
                }
                try {
                    relay(client, remote);
                } catch (IOException ignored) {
                } finally {
                    try { client.close(); } catch (IOException ignored) {}
                    try { remote.close(); } catch (IOException ignored) {}
                }
            }, "adb-fwd-pump");
            t.setDaemon(true);
            t.start();
        }
    }

    // ---------- endpoint plumbing ----------

    /** Abstraction over java.net sockets, android.net.LocalSocket, and
     * remote-transport streams. */
    interface Connection {
        InputStream getInputStream() throws IOException;

        OutputStream getOutputStream() throws IOException;

        void close() throws IOException;
    }

    private static final class TcpConnection implements Connection {
        final Socket mSocket;

        TcpConnection(Socket socket) {
            mSocket = socket;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return mSocket.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return mSocket.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            mSocket.close();
        }
    }

    private static final class LocalConnection implements Connection {
        final LocalSocket mSocket;

        LocalConnection(LocalSocket socket) {
            mSocket = socket;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return mSocket.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return mSocket.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            mSocket.close();
        }
    }

    /** Connection view over a remote-transport service stream. */
    private static final class RemoteStreamConnection implements Connection {
        final RemoteDevice.RemoteStream mStream;

        RemoteStreamConnection(RemoteDevice.RemoteStream stream) {
            mStream = stream;
        }

        @Override
        public InputStream getInputStream() {
            return mStream.inputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    mStream.write(b, off, len);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() throws IOException {
                    mStream.close();
                }
            };
        }

        @Override
        public void close() {
            mStream.close();
        }
    }

    private interface Listener {
        Connection accept() throws IOException;

        void close() throws IOException;
    }

    /** A listener plus the TCP port it actually bound (0 when not a tcp listener). */
    private static final class ListenerAndPort {
        final Listener listener;
        final int port;

        ListenerAndPort(Listener listener, int port) {
            this.listener = listener;
            this.port = port;
        }
    }

    private static ListenerAndPort openListener(String spec) throws IOException {
        if (spec.startsWith("tcp:")) {
            int port = parsePort(spec);
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            try {
                ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            } catch (IOException e) {
                throw new IOException(normalizeBindError(e.getMessage()));
            }
            final int bound = ss.getLocalPort();
            return new ListenerAndPort(new Listener() {
                @Override
                public Connection accept() throws IOException {
                    return new TcpConnection(ss.accept());
                }

                @Override
                public void close() throws IOException {
                    ss.close();
                }
            }, bound);
        }
        if (spec.startsWith("localabstract:")) {
            LocalServerSocket ls = new LocalServerSocket(spec.substring("localabstract:".length()));
            return new ListenerAndPort(new Listener() {
                @Override
                public Connection accept() throws IOException {
                    return new LocalConnection(ls.accept());
                }

                @Override
                public void close() throws IOException {
                    ls.close();
                }
            }, 0);
        }
        if (spec.startsWith("local:")) {
            LocalServerSocket ls = new LocalServerSocket(spec.substring("local:".length()));
            // Note: LocalServerSocket(String) binds an abstract socket; for a
            // filesystem path the name must carry the path, which the platform
            // maps through LocalSocketAddress parsing of the same string form.
            return new ListenerAndPort(new Listener() {
                @Override
                public Connection accept() throws IOException {
                    return new LocalConnection(ls.accept());
                }

                @Override
                public void close() throws IOException {
                    ls.close();
                }
            }, 0);
        }
        // AOSP socket_spec_listen appends the spec with no separator here.
        throw new IOException("unknown socket specification:" + spec);
    }

    private static Connection connectRemote(String spec) throws IOException {
        return connectRemote(spec, null);
    }

    /**
     * Connect a remote endpoint, either on the virtual device (loopback/local
     * sockets, as before) or through a network transport (the service string
     * is forwarded to the remote adbd, which connects on its own host).
     */
    static Connection connectRemote(String spec, String serial) throws IOException {
        if (serial != null && !DeviceServices.SERIAL.equals(serial)) {
            RemoteDevice d = AdbTransportManager.bySpec(serial);
            if (d == null || !d.isOnline()) {
                throw new IOException("device '" + serial + "' not found");
            }
            RemoteDevice.RemoteStream stream = d.open(spec);
            boolean accepted;
            try {
                accepted = stream.awaitAccepted(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stream.close();
                throw new IOException("interrupted while connecting to '" + spec + "'", e);
            }
            if (!accepted) {
                IOException err = stream.getError();
                stream.close();
                throw new IOException(err != null
                    ? "failed to connect to '" + spec + "': " + err.getMessage()
                    : "failed to connect to '" + spec + "' on '" + serial + "'");
            }
            return new RemoteStreamConnection(stream);
        }
        if (spec.startsWith("tcp:")) {
            int port = parsePort(spec);
            Socket s = new Socket();
            s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 5000);
            s.setTcpNoDelay(true);
            return new TcpConnection(s);
        }
        if (spec.startsWith("localabstract:")) {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(spec.substring("localabstract:".length())), 5000);
            return new LocalConnection(s);
        }
        if (spec.startsWith("local:")) {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(spec.substring("local:".length())), 5000);
            return new LocalConnection(s);
        }
        if (spec.startsWith("jdwp:") || spec.startsWith("vsock:")) {
            throw new IOException("failed to connect to '" + spec + "'");
        }
        throw new IOException("unsupported remote endpoint '" + spec + "'");
    }

    /**
     * Map Android's bind failure text onto AOSP's.
     *
     * network_loopback_server reports the bare strerror string, so a real host
     * says "cannot bind listener: Address already in use"; Android's
     * BindException wraps the same thing as
     * "bind failed: EADDRINUSE (Address already in use)".
     */
    private static String normalizeBindError(String message) {
        if (message == null) return "bind failed";
        String prefix = "bind failed: ";
        if (message.startsWith(prefix)) {
            int open = message.indexOf('(');
            int close = message.lastIndexOf(')');
            if (open > 0 && close > open) return message.substring(open + 1, close);
        }
        return message;
    }

    private static int parsePort(String spec) throws IOException {
        try {
            return Integer.parseInt(spec.substring(4).trim());
        } catch (NumberFormatException e) {
            throw new IOException("invalid tcp port in '" + spec + "'");
        }
    }

    /** Full-duplex relay between two connections. */
    static void relay(Connection a, Connection b) throws IOException {
        Thread t = new Thread(() -> {
            try {
                copy(b.getInputStream(), a.getOutputStream());
            } catch (IOException ignored) {
            } finally {
                try { a.close(); } catch (IOException ignored) {}
            }
        }, "adb-fwd-relay");
        t.setDaemon(true);
        t.start();
        copy(a.getInputStream(), b.getOutputStream());
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            out.flush();
        }
    }
}
