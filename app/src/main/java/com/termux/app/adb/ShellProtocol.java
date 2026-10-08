package com.termux.app.adb;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Shell v2 wire protocol (AOSP shell_protocol.h), packetizer and parser.
 *
 * Packet: 1 byte id + 4-byte little-endian length + data.
 * Ids: 0 stdin, 1 stdout, 2 stderr, 3 exit, 4 close-stdin, 5 window-size-change.
 * Window-size-change payloads are "%dx%d,%dx%d" strings and their declared
 * length includes the NUL terminator (strlen + 1), matching adb's client.
 */
final class ShellProtocol {

    static final byte ID_STDIN = 0;
    static final byte ID_STDOUT = 1;
    static final byte ID_STDERR = 2;
    static final byte ID_EXIT = 3;
    static final byte ID_CLOSE_STDIN = 4;
    static final byte ID_WINDOW_SIZE_CHANGE = 5;
    static final byte ID_INVALID = (byte) 255;

    /** Cap payloads at sync DATA max like AOSP (kMaxPayload = SYNC_DATA_MAX). */
    static final int MAX_PAYLOAD = 64 * 1024;

    private ShellProtocol() {
    }

    static void writePacket(OutputStream out, byte id, byte[] data, int len) throws IOException {
        writePacket(out, id, data, 0, len);
    }

    static void writePacket(OutputStream out, byte id, byte[] data, int off, int len)
        throws IOException {
        if (len > MAX_PAYLOAD) throw new IOException("shell packet too large");
        byte[] header = new byte[5];
        header[0] = id;
        putLe32(header, 1, len);
        synchronized (out) {
            out.write(header);
            if (len > 0) out.write(data, off, len);
            out.flush();
        }
    }

    static void writeExit(OutputStream out, int exitCode) throws IOException {
        writePacket(out, ID_EXIT, new byte[] {(byte) exitCode}, 1);
    }

    /**
     * Read one packet. Returns the id (0-255), -1 on stream end, or
     * ID_INVALID for protocol garbage (caller closes the connection).
     */
    static int readPacket(InputStream in, byte[] data, int[] length) throws IOException {
        byte[] header = new byte[5];
        readFully(in, header, 0, 5);
        byte id = header[0];
        int len = getLe32(header, 1);
        if (len < 0 || len > MAX_PAYLOAD) return ID_INVALID;
        readFully(in, data, 0, len);
        length[0] = len;
        return id & 0xff;
    }

    static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(buf, off + got, len - got);
            if (n < 0) throw new EOFException("EOF in shell packet");
            got += n;
        }
    }

    static void putLe32(byte[] buf, int off, int v) {
        buf[off] = (byte) (v & 0xff);
        buf[off + 1] = (byte) ((v >> 8) & 0xff);
        buf[off + 2] = (byte) ((v >> 16) & 0xff);
        buf[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    static int getLe32(byte[] buf, int off) {
        return (buf[off] & 0xff) | ((buf[off + 1] & 0xff) << 8)
            | ((buf[off + 2] & 0xff) << 16) | ((buf[off + 3] & 0xff) << 24);
    }

    /** Parse "rowsxcols,xpixypix" window-size payload; returns {rows, cols}. */
    static int[] parseWindowSize(byte[] data, int len) {
        try {
            String s = new String(data, 0, len > 0 && data[len - 1] == 0 ? len - 1 : len,
                java.nio.charset.StandardCharsets.US_ASCII);
            String sizePart = s.split(",")[0];
            String[] wh = sizePart.split("x");
            return new int[] {Integer.parseInt(wh[0]), Integer.parseInt(wh[1])};
        } catch (Exception e) {
            return null;
        }
    }
}
