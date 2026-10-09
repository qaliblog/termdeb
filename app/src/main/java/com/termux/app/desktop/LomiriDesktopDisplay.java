package com.termux.app.desktop;

import android.graphics.Bitmap;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Client side of the TermDeb desktop display bridge.
 *
 * <p>The Lomiri/Mir compositor runs inside the packaged Debian trixie guest. A small
 * guest program ({@code termdeb-mir-bridge}) captures the compositor output with the
 * Wayland {@code wlr-screencopy} protocol and writes raw frames into a shared memory
 * mapped file in the app data directory (which proot bind-mounts into the guest).
 * This class maps that same file from the Android side and hands frames to the
 * {@link LomiriDesktopView} for presentation on the app surface.
 *
 * <p>Input travels the other way over an abstract {@code AF_UNIX} socket owned by this
 * class; the guest bridge connects and injects the events into Mir using the Wayland
 * virtual keyboard/pointer protocols.
 *
 * <p>This is deliberately <b>not</b> VNC or any other remote-desktop protocol: frames
 * are exchanged through local shared memory and input through a local socket, with no
 * network transport and no frame encoding.
 *
 * <p>Shared framebuffer file layout (little-endian, 32-byte header):
 * <pre>
 *   uint32 magic    = 0x42464454 ("TDFB")
 *   uint32 version  = 1
 *   uint32 width    frame width  (px)
 *   uint32 height   frame height (px)
 *   uint32 stride   bytes per row
 *   uint32 format   = 1 (ARGB_8888, Android Bitmap word order)
 *   uint32 seq      incremented by the guest on every completed frame
 *   uint32 flags    bit 0 = producer ready
 * </pre>
 * followed by {@code height * stride} pixel bytes.
 */
public final class LomiriDesktopDisplay {

    private static final String LOG_TAG = "LomiriDesktopDisplay";

    public static final String FB_FILE_NAME = "fb.buf";
    /** Abstract AF_UNIX socket the guest bridge connects to for injected input. */
    public static final String INPUT_SOCKET_NAME = "termdeb-desktop-input";

    public static final int MAGIC = 0x42464454;
    public static final int VERSION = 1;
    public static final int FORMAT_ARGB_8888 = 1;
    public static final int HEADER_SIZE = 32;

    public static final int FLAG_PRODUCER_READY = 0x1;

    // Input record layout (little-endian, 24 bytes). Mirrored in termdeb-mir-bridge.c.
    public static final int INPUT_TYPE_TOUCH = 1;
    public static final int INPUT_TYPE_KEY = 2;
    public static final int INPUT_TYPE_TEXT = 3;

    public static final int TOUCH_ACTION_DOWN = 0;
    public static final int TOUCH_ACTION_MOVE = 1;
    public static final int TOUCH_ACTION_UP = 2;

    public static final int INPUT_RECORD_SIZE = 24;

    private final File mFramebufferFile;
    private final int mMaxWidth;
    private final int mMaxHeight;

    private RandomAccessFile mRandomAccessFile;
    private FileChannel mChannel;
    private MappedByteBuffer mMap;
    private ByteBuffer mHeader;
    private ByteBuffer mPixels;

    private Bitmap mFrame;
    private int mFrameWidth;
    private int mFrameHeight;
    private int mLastSeq = -1;

    private LocalServerSocket mInputServer;
    private volatile LocalSocket mInputClient;
    private volatile OutputStream mInputOut;
    private Thread mInputAcceptThread;

    public LomiriDesktopDisplay(File runtimeDir, int maxWidth, int maxHeight) {
        mFramebufferFile = new File(runtimeDir, FB_FILE_NAME);
        mMaxWidth = maxWidth;
        mMaxHeight = maxHeight;
    }

    public static File runtimeDir(File appFilesDir) {
        return new File(appFilesDir, "termdeb-desktop");
    }

    // ---------------------------------------------------------------------------------------------
    // Framebuffer
    // ---------------------------------------------------------------------------------------------

    /**
     * Create/truncate the shared framebuffer file, publish an initial header describing the
     * geometry limits for the guest producer, and map it read/write.
     */
    public synchronized void open() throws IOException {
        if (mMap != null) return;

        File parent = mFramebufferFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory())
            throw new IOException("Could not create desktop runtime dir: " + parent);

        long length = (long) HEADER_SIZE + (long) mMaxWidth * mMaxHeight * 4;
        mRandomAccessFile = new RandomAccessFile(mFramebufferFile, "rw");
        if (mRandomAccessFile.length() != length) mRandomAccessFile.setLength(length);
        mChannel = mRandomAccessFile.getChannel();
        mMap = mChannel.map(FileChannel.MapMode.READ_WRITE, 0, length);
        mMap.order(ByteOrder.LITTLE_ENDIAN);

        mHeader = mMap.duplicate();
        mHeader.order(ByteOrder.LITTLE_ENDIAN);
        mPixels = mMap.duplicate();
        mPixels.order(ByteOrder.nativeOrder());

        // Publish the initial geometry so the guest bridge can pick a matching size.
        mHeader.putInt(0, MAGIC);
        mHeader.putInt(4, VERSION);
        mHeader.putInt(8, mMaxWidth);
        mHeader.putInt(12, mMaxHeight);
        mHeader.putInt(16, mMaxWidth * 4);
        mHeader.putInt(20, FORMAT_ARGB_8888);
        mHeader.putInt(24, 0);
        mHeader.putInt(28, 0);

        Log.i(LOG_TAG, "Framebuffer mapped: " + mFramebufferFile + " (" + length + " bytes)");
    }

    public synchronized boolean isProducerReady() {
        return mHeader != null && (mHeader.getInt(28) & FLAG_PRODUCER_READY) != 0;
    }

    /**
     * If the guest produced a new frame since the last call, copy it into a reused
     * {@link Bitmap} and return it, otherwise return {@code null}.
     */
    public synchronized int getFrameWidth() {
        return mFrameWidth;
    }

    public synchronized int getFrameHeight() {
        return mFrameHeight;
    }

    @Nullable
    public synchronized Bitmap pollFrame() {
        if (mMap == null) return null;
        if (mHeader.getInt(0) != MAGIC) return null;

        int seq = mHeader.getInt(24);
        if (seq == mLastSeq) return null;

        int width = mHeader.getInt(8);
        int height = mHeader.getInt(12);
        int stride = mHeader.getInt(16);
        int format = mHeader.getInt(20);
        if (format != FORMAT_ARGB_8888 || width <= 0 || height <= 0) return null;

        long frameBytes = (long) height * stride;
        long mapCapacity = (long) HEADER_SIZE + (long) mMaxWidth * mMaxHeight * 4;
        if (stride < (long) width * 4 || HEADER_SIZE + frameBytes > mapCapacity) {
            Log.w(LOG_TAG, "Frame geometry out of range: " + width + "x" + height + " stride=" + stride);
            return null;
        }

        if (mFrame == null || mFrameWidth != width || mFrameHeight != height) {
            mFrame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            mFrameWidth = width;
            mFrameHeight = height;
            Log.i(LOG_TAG, "Frame surface: " + width + "x" + height);
        }

        mPixels.clear();
        mPixels.position(HEADER_SIZE);
        mPixels.limit((int) (HEADER_SIZE + frameBytes));
        try {
            mFrame.copyPixelsFromBuffer(mPixels);
        } catch (RuntimeException e) {
            Log.w(LOG_TAG, "copyPixelsFromBuffer failed", e);
            return null;
        }

        mLastSeq = seq;
        return mFrame;
    }

    // ---------------------------------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------------------------------

    /** Start the abstract AF_UNIX server the guest bridge connects to for input injection. */
    public synchronized void startInputServer() {
        if (mInputServer != null) return;
        try {
            mInputServer = new LocalServerSocket(INPUT_SOCKET_NAME);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Could not create input socket " + INPUT_SOCKET_NAME, e);
            return;
        }

        mInputAcceptThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    LocalSocket client = mInputServer.accept();
                    mInputOut = client.getOutputStream();
                    mInputClient = client;
                    Log.i(LOG_TAG, "Guest input bridge connected");
                } catch (IOException e) {
                    if (!Thread.currentThread().isInterrupted())
                        Log.w(LOG_TAG, "Input accept failed", e);
                    return;
                }
            }
        }, "termdeb-desktop-input");
        mInputAcceptThread.setDaemon(true);
        mInputAcceptThread.start();
    }

    public synchronized boolean hasInputClient() {
        return mInputOut != null;
    }

    /** Send a touch event; coordinates are in frame pixels. */
    public void sendTouch(int action, float x, float y) {
        int px = Math.max(0, Math.min(mMaxWidth - 1, Math.round(x)));
        int py = Math.max(0, Math.min(mMaxHeight - 1, Math.round(y)));
        sendRecord(INPUT_TYPE_TOUCH, action, px, py, 0, 0);
    }

    /** Send a key press/release using a Linux input (evdev) key code. */
    public void sendKey(boolean press, int evdevCode, int modifiers) {
        sendRecord(INPUT_TYPE_KEY, press ? 0 : 1, 0, 0, evdevCode, modifiers);
    }

    /** Send a Unicode code point as text input. */
    public void sendText(int codePoint) {
        sendRecord(INPUT_TYPE_TEXT, 0, 0, 0, codePoint, 0);
    }

    private synchronized void sendRecord(int type, int action, int x, int y, int code, int modifiers) {
        OutputStream out = mInputOut;
        if (out == null) return;

        ByteBuffer record = ByteBuffer.allocate(INPUT_RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        record.put((byte) type);
        record.put((byte) action);
        record.putShort((short) 0);
        record.putInt(x);
        record.putInt(y);
        record.putInt(code);
        record.putInt(modifiers);

        try {
            out.write(record.array());
            out.flush();
        } catch (IOException e) {
            Log.w(LOG_TAG, "Input write failed, dropping client", e);
            closeInputClient();
        }
    }

    private synchronized void closeInputClient() {
        LocalSocket client = mInputClient;
        mInputClient = null;
        mInputOut = null;
        if (client != null) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    public synchronized void close() {
        if (mInputServer != null) {
            try {
                mInputServer.close();
            } catch (IOException ignored) {
            }
            mInputServer = null;
        }
        if (mInputAcceptThread != null) {
            mInputAcceptThread.interrupt();
            mInputAcceptThread = null;
        }
        closeInputClient();

        mFrame = null;
        mLastSeq = -1;
        mHeader = null;
        mPixels = null;
        mMap = null;
        try {
            if (mChannel != null) mChannel.close();
            if (mRandomAccessFile != null) mRandomAccessFile.close();
        } catch (IOException ignored) {
        }
        mChannel = null;
        mRandomAccessFile = null;
    }
}
