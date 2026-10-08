/*
 * TermDeb ADB bridge — real network ADB transport to a remote adbd.
 *
 * Connects to an Android device's adbd over TCP (legacy `adb tcpip 5555` /
 * "ADB over TCP") exactly like the official client's network transports do:
 *
 *   CNXN → (AUTH token → signed token → CNXN) → A_OPEN "shell:..."
 *   → A_OKAY → A_WRTE/A_OKAY streaming → A_CLSE
 *
 * Verified against AOSP packages/modules/adb transport.cpp / transport_local.cpp /
 * adb_auth_host.cpp. The remote device is NOT emulated: `adb connect` only
 * succeeds when the real adbd answers and authorizes the key; `adb devices`
 * reflects the actual live transports.
 */
package com.termux.app.adb.remote;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One live ADB network transport to a remote adbd. */
public final class RemoteDevice implements Closeable {

    public enum AuthState {
        /** CNXN not completed. */
        CONNECTING,
        /** adbd sent A_AUTH TOKEN; waiting for a signed reply to be accepted. */
        UNAUTHORIZED,
        /** CNXN completed as "device". */
        CONNECTED,
        /** Socket closed / connect failed. */
        OFFLINE
    }

    private static final AtomicInteger sNextLocalId = new AtomicInteger(1);

    private final Socket mSocket;
    private final InputStream mIn;
    private final OutputStream mOut;
    private final String mSpec;
    private final KeyProvider mKeyProvider;
    private final Listener mListener;

    private volatile AuthState mAuthState = AuthState.CONNECTING;
    private volatile String mSerial;
    private volatile String mBanner = "";
    private volatile IOException mConnectError;

    /** Remote's announced max payload (CNXN arg1), clamped to our ceiling. */
    private volatile int mRemoteMaxPayload = AdbPacket.MAX_PAYLOAD;

    /** Pre-0x01000001 adbd requires one OKAY per WRTE (no pipelining). */
    private volatile boolean mLockstepWrites = false;

    /** Set once we have offered our public key (deadline error wording). */
    private volatile boolean mSentPublicKey = false;

    /** remoteId → stream for this transport. */
    private final ConcurrentHashMap<Integer, RemoteStream> mStreams = new ConcurrentHashMap<>();
    /** localId → stream (for canceling pending opens). */
    private final ConcurrentHashMap<Integer, RemoteStream> mPendingOpens = new ConcurrentHashMap<>();
    private final AtomicBoolean mClosed = new AtomicBoolean(false);

    private Thread mReaderThread;

    /** RSA key source (AdbKeyStore supplies the app-stored pair). */
    public interface KeyProvider {
        AdbKeyPair get() throws Exception;
    }

    public interface Listener {
        /** The transport's auth/state changed (any thread). */
        void onStateChanged(RemoteDevice device);

        /** A stream was closed by the remote end (any thread). */
        void onStreamClosed(RemoteDevice device, RemoteStream stream);
    }

    /**
     * Handler for streams the REMOTE end opens toward us (any thread).
     *
     * This is the host-side half of `adb reverse` against a real device: the
     * remote adbd listens on the device endpoint and, when a device-side
     * connection arrives, opens an A_OPEN whose service string is the HOST
     * endpoint spec (e.g. "tcp:18765", "localabstract:name") — the same
     * service-string connect semantics the official adb server's smart socket
     * implements (AOSP socket_spec_connect).
     */
    public interface IncomingHandler {
        void onIncomingStream(RemoteDevice device, RemoteStream stream);
    }

    private volatile IncomingHandler mIncomingHandler;

    public void setIncomingHandler(IncomingHandler handler) {
        mIncomingHandler = handler;
    }

    private RemoteDevice(Socket socket, String spec, KeyProvider keys, Listener listener)
        throws IOException {
        mSocket = socket;
        mIn = socket.getInputStream();
        mOut = socket.getOutputStream();
        mSpec = spec;
        mKeyProvider = keys;
        mListener = listener;
        mSerial = spec;
    }

    /**
     * Connect and handshake. Returns the transport after the CNXN completed or
     * after the device requested auth (state UNAUTHORIZED). Throws IOException
     * when the endpoint is unreachable or speaks no ADB protocol.
     */
    public static RemoteDevice connect(String host, int port, KeyProvider keys,
                                       Listener listener, int timeoutMs) throws IOException {
        String spec = host + ":" + port;
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setTcpNoDelay(true);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw new IOException("failed to connect to '" + spec + "': " + e.getMessage(), e);
        }
        return connectOverSocket(socket, spec, keys, listener, timeoutMs);
    }

    /**
     * Variant of {@link #connect} for transports whose socket is already
     * established and security-wrapped — the Android 11+ Wireless Debugging
     * path hands us a TLS 1.3 socket (client-cert identity, adb_wifi.cpp
     * register_socket_transport use_tls=true). The CNXN handshake runs
     * unchanged inside TLS: adbd's secure transports skip the legacy A_AUTH
     * token flow (daemon/adb_wifi.cpp adbd_wifi_secure_connect calls
     * handle_online + send_connect directly) because the client certificate
     * matched against the pairing record IS the authentication.
     */
    public static RemoteDevice connectOverSocket(Socket socket, String spec,
                                                 KeyProvider keys, Listener listener,
                                                 int timeoutMs) throws IOException {
        RemoteDevice device = new RemoteDevice(socket, spec, keys, listener);
        try {
            device.handshake(timeoutMs);
        } catch (IOException e) {
            device.closeQuietly();
            throw e;
        }
        device.startReader();
        return device;
    }

    /** CNXN + AUTH exchange on the connect thread. */
    private void handshake(int timeoutMs) throws IOException {
        mSocket.setSoTimeout(timeoutMs);
        // Initial CNXN (AOSP connect_service: version, max payload, "host::" system).
        writePacket(AdbPacket.of(AdbPacket.A_CNXN, AdbPacket.CONNECT_VERSION,
            AdbPacket.MAX_PAYLOAD, "host::\0"));

        // Set once we have sent our public key because the device requires
        // interactive authorization; the deadline error must then say so.
        mSentPublicKey = false;
        int tokenRounds = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("failed to connect to '" + mSpec + "': handshake timeout");
            }
            AdbPacket p;
            try {
                p = AdbPacket.readFrom(mIn);
            } catch (java.net.SocketTimeoutException e) {
                if (mSentPublicKey) {
                    throw new IOException("unauthorized device: accept the debugging/RSA"
                        + " fingerprint dialog on '" + mSpec + "', then connect again");
                }
                throw new IOException("failed to connect to '" + mSpec + "': handshake timeout", e);
            }
            if (p == null) {
                throw new IOException("failed to connect to '" + mSpec + "': connection closed during handshake");
            }
            switch (p.cmd) {
                case AdbPacket.A_CNXN:
                    // Authorized: adbd accepted our key (or had no auth enabled).
                    if (p.arg0 < 0x01000000) {
                        throw new IOException(String.format(
                            "adb: adbd protocol version %#010x is too old (min %#010x)",
                            p.arg0, 0x01000000));
                    }
                    // Honor the remote's max payload (CNXN arg1), clamped to
                    // our own ceiling; pre-0x01000001 adbd is lockstep.
                    mRemoteMaxPayload = Math.max(1024,
                        Math.min(AdbPacket.MAX_PAYLOAD, p.arg1));
                    mLockstepWrites = p.arg0 < AdbPacket.A_VERSION_SKIP_CHECKSUM;
                    mBanner = new String(p.payload, java.nio.charset.StandardCharsets.UTF_8);
                    mAuthState = AuthState.CONNECTED;
                    notifyState();
                    return;
                case AdbPacket.A_AUTH:
                    if (p.arg0 == AdbPacket.ADB_AUTH_TOKEN) {
                        // adbd challenges with a 20-byte token (TOKEN_SIZE).
                        try {
                            AdbKeyPair pair = mKeyProvider.get();
                            if (pair == null) {
                                throw new IOException("no ADB key available to sign the auth token");
                            }
                            byte[] sig = pair.signToken(p.payload);
                            writePacket(AdbPacket.of(AdbPacket.A_AUTH,
                                AdbPacket.ADB_AUTH_SIGNATURE, 0, sig));
                        } catch (Exception e) {
                            throw new IOException("adb: failed to sign auth token: " + e.getMessage(), e);
                        }
                        // AOSP client flow with a single key: if adbd did not
                        // accept the signature it sends another TOKEN; after
                        // the second challenge we offer the public key for the
                        // user to authorize (send_auth_response key exhaustion
                        // → send_auth_publickey).
                        if (++tokenRounds >= 2 && !mSentPublicKey) {
                            sendPublicKey();
                        }
                        mAuthState = AuthState.UNAUTHORIZED;
                        notifyState();
                    } else if (p.arg0 == AdbPacket.ADB_AUTH_RSAPUBLICKEY) {
                        // adbd wants our public key (user must accept the RSA
                        // fingerprint dialog on the device).
                        sendPublicKey();
                        mAuthState = AuthState.UNAUTHORIZED;
                        notifyState();
                    } else {
                        throw new IOException("adb: unexpected A_AUTH type " + p.arg0);
                    }
                    break;
                default:
                    throw new IOException(String.format(
                        "adb: unexpected packet %#010x during handshake", p.cmd));
            }
        }
    }

    /** Send our Android-format public key (AOSP send_auth_publickey). */
    private void sendPublicKey() throws IOException {
        try {
            AdbKeyPair pair = mKeyProvider.get();
            if (pair == null) {
                throw new IOException("no ADB key available");
            }
            writePacket(AdbPacket.of(AdbPacket.A_AUTH,
                AdbPacket.ADB_AUTH_RSAPUBLICKEY, 0,
                pair.getAndroidPubkeyLine("termdeb")));
            mSentPublicKey = true;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("adb: failed to send public key: " + e.getMessage(), e);
        }
    }

    private void startReader() {
        mReaderThread = new Thread(this::readerLoop, "adb-remote-" + mSpec);
        mReaderThread.setDaemon(true);
        mReaderThread.start();
    }

    private void readerLoop() {
        try {
            mSocket.setSoTimeout(0);
            while (!mClosed.get()) {
                AdbPacket p = AdbPacket.readFrom(mIn);
                if (p == null) break;
                handlePacket(p);
            }
        } catch (IOException | RuntimeException e) {
            if (!mClosed.get()) {
                mConnectError = e instanceof IOException ? (IOException) e : new IOException(e);
            }
        } finally {
            shutdown();
        }
    }

    private void handlePacket(AdbPacket p) throws IOException {
        switch (p.cmd) {
            case AdbPacket.A_OKAY: {
                RemoteStream s = mStreams.get(p.arg1); // arg1 = our local id
                if (s == null) s = mPendingOpens.remove(p.arg1);
                if (s != null) {
                    s.onRemoteAccepted(p.arg0);
                    notifyState();
                }
                break;
            }
            case AdbPacket.A_WRTE: {
                RemoteStream s = mStreams.get(p.arg1);
                if (s != null) {
                    s.onPayload(p.payload);
                    writePacket(AdbPacket.of(AdbPacket.A_OKAY, s.localId(), p.arg0));
                }
                break;
            }
            case AdbPacket.A_CLSE: {
                RemoteStream s = mStreams.remove(p.arg1);
                if (s == null) s = mPendingOpens.remove(p.arg1);
                if (s != null) {
                    s.onRemoteClosed();
                    mListener.onStreamClosed(this, s);
                }
                break;
            }
            case AdbPacket.A_CNXN:
                // Remote rebooted adbd / reconnected: streams are dead. A CNXN
                // here also completes authorization if the user accepted the
                // RSA dialog after our handshake reported UNAUTHORIZED.
                if (p.arg0 >= 0x01000000) {
                    mRemoteMaxPayload = Math.max(1024,
                        Math.min(AdbPacket.MAX_PAYLOAD, p.arg1));
                    mLockstepWrites = p.arg0 < AdbPacket.A_VERSION_SKIP_CHECKSUM;
                }
                mBanner = new String(p.payload, java.nio.charset.StandardCharsets.UTF_8);
                mAuthState = AuthState.CONNECTED;
                reconnectAllStreams();
                notifyState();
                break;
            case AdbPacket.A_OPEN: {
                // Remote-initiated stream (reverse flow): accept it and hand
                // the service string to the incoming handler.
                String service = new String(p.payload, java.nio.charset.StandardCharsets.UTF_8);
                int localId = sNextLocalId.incrementAndGet();
                RemoteStream s = new RemoteStream(this, localId, service);
                s.onRemoteAccepted(p.arg0);
                writePacket(AdbPacket.of(AdbPacket.A_OKAY, localId, p.arg0));
                IncomingHandler handler = mIncomingHandler;
                if (handler != null) {
                    handler.onIncomingStream(this, s);
                } else {
                    s.close();
                }
                break;
            }
            case AdbPacket.A_AUTH:
                // Re-auth (device rebooted and lost our authorization).
                mAuthState = AuthState.UNAUTHORIZED;
                notifyState();
                break;
            default:
                // Unknown command: ignore, like adbd's handle_packet default.
                break;
        }
    }

    private void reconnectAllStreams() {
        for (RemoteStream s : mStreams.values()) {
            s.onRemoteClosed();
        }
        mStreams.clear();
    }

    /**
     * Open a stream (A_OPEN). Returns immediately; the stream reports
     * readiness through its own state. Service strings are exactly the ones a
     * real adbd accepts ("shell:...", "sync:", "tcp:1234", ...).
     */
    public RemoteStream open(String service) {
        int localId = sNextLocalId.incrementAndGet();
        RemoteStream s = new RemoteStream(this, localId, service);
        mPendingOpens.put(localId, s);
        try {
            writePacket(AdbPacket.of(AdbPacket.A_OPEN, localId, 0, service));
        } catch (IOException e) {
            mPendingOpens.remove(localId);
            s.onLocalError(e);
        }
        return s;
    }

    void sendOkay(int localId, int remoteId) throws IOException {
        writePacket(AdbPacket.of(AdbPacket.A_OKAY, localId, remoteId));
    }

    void sendWrite(int localId, int remoteId, byte[] data, int off, int len) throws IOException {
        if (len > mRemoteMaxPayload) {
            throw new IOException("adb: payload too large for remote maxdata: " + len);
        }
        byte[] chunk = (off == 0 && len == data.length) ? data : java.util.Arrays.copyOfRange(data, off, off + len);
        writePacket(AdbPacket.of(AdbPacket.A_WRTE, localId, remoteId, chunk));
        if (mLockstepWrites) {
            // Pre-0x01000001 adbd: block until the matching A_OKAY arrives.
            RemoteStream s = mStreams.get(localId);
            if (s != null) {
                s.awaitWriteAck();
            }
        }
    }

    void sendClose(int localId, int remoteId) throws IOException {
        writePacket(AdbPacket.of(AdbPacket.A_CLSE, localId, remoteId));
    }

    private synchronized void writePacket(AdbPacket p) throws IOException {
        if (mClosed.get()) throw new IOException("transport closed");
        p.writeTo(mOut);
    }

    void unregister(RemoteStream s) {
        mStreams.remove(s.localId());
        mPendingOpens.remove(s.localId());
    }

    private void notifyState() {
        Listener l = mListener;
        if (l != null) l.onStateChanged(this);
    }

    private void shutdown() {
        if (!mClosed.compareAndSet(false, true)) return;
        mAuthState = AuthState.OFFLINE;
        for (RemoteStream s : mStreams.values()) {
            s.onRemoteClosed();
        }
        mStreams.clear();
        mPendingOpens.clear();
        closeQuietly();
        notifyState();
    }

    @Override
    public void close() {
        shutdown();
        if (mReaderThread != null) {
            mReaderThread.interrupt();
        }
    }

    private void closeQuietly() {
        try {
            mSocket.close();
        } catch (IOException ignored) {
        }
    }

    // ---------- accessors ----------

    public AuthState getAuthState() {
        return mAuthState;
    }

    /** "host:port" this transport was opened with. */
    public String getSpec() {
        return mSpec;
    }

    public IOException getConnectError() {
        return mConnectError;
    }

    /** Serial reported by the device once a shell-like stream told us; falls
     * back to the spec until then (mirrors `adb connect` where the serial is
     * the address). */
    public String getSerial() {
        return mSerial;
    }

    public String getBanner() {
        return mBanner;
    }

    public boolean isOnline() {
        return mAuthState == AuthState.CONNECTED;
    }

    /** Max payload we may write per WRTE (the remote's CNXN maxdata). */
    public int getRemoteMaxPayload() {
        return mRemoteMaxPayload;
    }

    /** Number of currently open streams (for status display). */
    public int openStreamCount() {
        return mStreams.size();
    }

    /** Streams opened and accepted by the remote end (for diagnostics). */
    public List<RemoteStream> streams() {
        return new ArrayList<>(mStreams.values());
    }

    /**
     * One A_OPEN stream over the transport ("shell:...", "sync:", "tcp:...", ...).
     * Single-threaded use per direction is assumed for write; reads are
     * callback-driven from the transport reader thread.
     */
    public static final class RemoteStream {
        private final RemoteDevice mDevice;
        private final int mLocalId;
        private final String mService;
        private volatile int mRemoteId = -1;
        private volatile boolean mAccepted = false;
        private volatile boolean mClosed = false;
        private volatile IOException mError;

        private final java.util.ArrayDeque<byte[]> mBuffer = new java.util.ArrayDeque<>();
        private volatile int mBuffered = 0;
        private final Object mReadLock = new Object();

        /** WRTEs sent but not yet OKAYed (lockstep mode only). Guarded by
         * mReadLock. */
        private int mInFlightWrites = 0;

        private RemoteStream(RemoteDevice device, int localId, String service) {
            mDevice = device;
            mLocalId = localId;
            mService = service;
        }

        public int localId() {
            return mLocalId;
        }

        public String getService() {
            return mService;
        }

        /** Whether the remote end accepted the open and the stream is live. */
        public boolean isAccepted() {
            return mAccepted && !mClosed;
        }

        public IOException getError() {
            return mError;
        }

        /** Blocks until the remote accepts or refuses/closes. True once the
         * A_OKAY was seen, even if the stream closed right afterwards (the
         * exchange may legitimately complete that fast). */
        public boolean awaitAccepted(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!mAccepted && !mClosed && mError == null) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) return mAccepted;
                Thread.sleep(10);
            }
            return mAccepted;
        }

        void onRemoteAccepted(int remoteId) {
            mRemoteId = remoteId;
            mAccepted = true;
            mDevice.mPendingOpens.remove(mLocalId);
            mDevice.mStreams.put(mLocalId, this);
            synchronized (mReadLock) {
                if (mInFlightWrites > 0) {
                    mInFlightWrites--;
                }
                mReadLock.notifyAll();
            }
        }

        /** Lockstep flow control (pre-0x01000001 adbd): wait for the OKAY
         * matching a sent WRTE. */
        void awaitWriteAck() throws IOException {
            long deadline = System.currentTimeMillis() + 30_000;
            synchronized (mReadLock) {
                while (mInFlightWrites > 0) {
                    if (mError != null) throw mError;
                    if (mClosed) {
                        throw new IOException("stream closed before write was acknowledged");
                    }
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) {
                        throw new IOException("timed out waiting for A_OKAY (lockstep write)");
                    }
                    try {
                        mReadLock.wait(Math.min(remain, 1000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", e);
                    }
                }
            }
        }

        void onPayload(byte[] data) {
            if (data.length == 0) return;
            synchronized (mReadLock) {
                mBuffer.addLast(data);
                mBuffered += data.length;
                mReadLock.notifyAll();
            }
        }

        void onRemoteClosed() {
            mClosed = true;
            synchronized (mReadLock) {
                mReadLock.notifyAll();
            }
        }

        void onLocalError(IOException e) {
            mError = e;
            mClosed = true;
            synchronized (mReadLock) {
                mReadLock.notifyAll();
            }
        }

        /** Read up to buf.length bytes; returns -1 when the stream is closed
         * and drained. Blocks. */
        public int read(byte[] buf) throws IOException {
            synchronized (mReadLock) {
                while (mBuffered == 0) {
                    if (mError != null) throw mError;
                    if (mClosed) return -1;
                    try {
                        mReadLock.wait(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", e);
                    }
                }
                byte[] first = mBuffer.peekFirst();
                int n = Math.min(first.length, buf.length);
                System.arraycopy(first, 0, buf, 0, n);
                if (n == first.length) {
                    mBuffer.removeFirst();
                } else {
                    byte[] rest = new byte[first.length - n];
                    System.arraycopy(first, n, rest, 0, rest.length);
                    mBuffer.removeFirst();
                    mBuffer.addFirst(rest);
                }
                mBuffered -= n;
                return n;
            }
        }

        /** Write bytes to the remote service (blocks on socket writes). */
        public void write(byte[] data, int off, int len) throws IOException {
            if (mClosed) throw new IOException("stream closed");
            if (!isAccepted()) {
                throw new IOException("stream not accepted by remote");
            }
            int sent = 0;
            while (sent < len) {
                int n = Math.min(mDevice.mRemoteMaxPayload, len - sent);
                synchronized (mReadLock) {
                    mInFlightWrites++;
                }
                try {
                    mDevice.sendWrite(mLocalId, mRemoteId, data, off + sent, n);
                } catch (IOException e) {
                    synchronized (mReadLock) {
                        mInFlightWrites--;
                    }
                    throw e;
                }
                sent += n;
            }
        }

        /** InputStream view over the stream (blocks; -1 at close+drain). */
        public java.io.InputStream inputStream() {
            return new java.io.InputStream() {
                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    int n = RemoteStream.this.read(one);
                    return n < 0 ? -1 : (one[0] & 0xff);
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (len == 0) return 0;
                    byte[] tmp = new byte[len];
                    int n = RemoteStream.this.read(tmp);
                    if (n < 0) return -1;
                    System.arraycopy(tmp, 0, b, off, n);
                    return n;
                }
            };
        }

        /** Close our side (A_CLSE). Idempotent. */
        public void close() {
            if (mClosed) return;
            mClosed = true;
            mDevice.unregister(this);
            try {
                if (mRemoteId != -1) {
                    mDevice.sendClose(mLocalId, mRemoteId);
                }
            } catch (IOException ignored) {
            }
            synchronized (mReadLock) {
                mReadLock.notifyAll();
            }
        }
    }
}
