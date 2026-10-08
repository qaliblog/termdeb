package com.termux.app.adb;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The bridge's smart-protocol server (AOSP adb server role).
 *
 * Listens on 127.0.0.1:<port>, reads the 4-hex-digit length-prefixed service
 * string from every connection and hands it to HostServices. One thread per
 * connection, like AOSP's fdevent socket server; the guest has a handful of
 * concurrent clients at most.
 */
final class AdbServer {

    private static final String LOG_TAG = TermdebAdbBridge.LOG_TAG;

    private final int mPort;
    private final HostServices mHostServices;
    private ServerSocket mServerSocket;
    private Thread mAcceptThread;
    private final AtomicLong mConnectionCounter = new AtomicLong();
    private volatile boolean mRunning;

    AdbServer(int port) {
        mPort = port;
        mHostServices = new HostServices();
    }

    /** Bind and start accepting. Returns false if the port could not be bound. */
    synchronized boolean start() {
        if (mRunning) return true;
        try {
            mServerSocket = new ServerSocket(mPort, 16, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            TermdebAdbBridge.logError(LOG_TAG,
                "Cannot bind 127.0.0.1:" + mPort + ": " + e.getMessage());
            return false;
        }
        mRunning = true;
        mAcceptThread = new Thread(this::acceptLoop, "adb-server-accept");
        mAcceptThread.setDaemon(true);
        mAcceptThread.start();
        TermdebAdbBridge.logInfo(LOG_TAG, "ADB bridge server listening on 127.0.0.1:" + mPort);
        return true;
    }

    private void acceptLoop() {
        while (mRunning) {
            try {
                Socket socket = mServerSocket.accept();
                long id = mConnectionCounter.incrementAndGet();
                Thread t = new Thread(() -> handleConnection(socket), "adb-conn-" + id);
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                // host:kill closes the listener deliberately; log only the
                // unexpected failures.
                if (mRunning && !HostServices.isShutdownCaused()) {
                    TermdebAdbBridge.logWarn(LOG_TAG, "accept() failed: " + e.getMessage());
                }
                break;
            }
        }
        mRunning = false;
    }

    private void handleConnection(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            mHostServices.handleSmartProtocol(socket);
        } catch (Exception e) {
            TermdebAdbBridge.logDebug(LOG_TAG, "Connection handler error: " + e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    synchronized void stop() {
        mRunning = false;
        if (mServerSocket != null) {
            try {
                mServerSocket.close();
            } catch (IOException ignored) {
            }
            mServerSocket = null;
        }
        if (mAcceptThread != null) {
            mAcceptThread.interrupt();
            mAcceptThread = null;
        }
        ForwardRegistry.killAllListeners();
        TermdebAdbBridge.logInfo(LOG_TAG, "ADB bridge server stopped");
    }

    boolean isRunning() {
        return mRunning;
    }
}
