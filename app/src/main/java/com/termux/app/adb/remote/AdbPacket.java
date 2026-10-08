/*
 * TermDeb ADB bridge — ADB packet framing (AOSP transport-local protocol).
 *
 * Wire format (verified against AOSP packages/modules/adb, adb.h / types.h /
 * transport.cpp): 24-byte little-endian header followed by `data_len` bytes.
 *
 *   uint32 cmd        A_CNXN / A_AUTH / A_OPEN / A_OKAY / A_CLSE / A_WRTE
 *   uint32 arg0       command argument
 *   uint32 arg1       command argument
 *   uint32 data_len   length of the payload that follows
 *   uint32 data_crc   checksum of payload (sum of bytes, LE)
 *   uint32 magic      cmd ^ 0xffffffff
 *
 * The remote transport is a REAL network ADB connection to a remote adbd
 * (TCP 5555 or Wireless Debugging): A_CNXN handshake, A_AUTH token signing
 * with the stored RSA key, A_OPEN/A_OKAY/A_WRTE/A_CLSE streams. No emulation:
 * whatever the remote device answers is what the client sees.
 */
package com.termux.app.adb.remote;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** One ADB packet (header + optional payload), little-endian. */
public final class AdbPacket {

    // Command constants (AOSP adb.h).
    public static final int A_CNXN = ('C' << 24) | ('N' << 16) | ('X' << 8) | 'N';
    public static final int A_AUTH = ('A' << 24) | ('U' << 16) | ('T' << 8) | 'H';
    public static final int A_OPEN = ('O' << 24) | ('P' << 16) | ('E' << 8) | 'N';
    public static final int A_OKAY = ('O' << 24) | ('K' << 16) | ('A' << 8) | 'Y';
    public static final int A_CLSE = ('C' << 24) | ('L' << 16) | ('S' << 8) | 'E';
    public static final int A_WRTE = ('W' << 24) | ('R' << 16) | ('T' << 8) | 'E';

    /** A_CNXN version we speak (AOSP A_VERSION). */
    public static final int CONNECT_VERSION = 0x01000001;

    /** Version that introduced checksum-skipping pipelined writes (Dec 2017). */
    public static final int A_VERSION_SKIP_CHECKSUM = 0x01000001;

    /** A_AUTH sub-commands (AOSP adb_auth.h). */
    public static final int ADB_AUTH_TOKEN = 1;
    public static final int ADB_AUTH_SIGNATURE = 2;
    public static final int ADB_AUTH_RSAPUBLICKEY = 3;

    /** Max payload we will send in one A_WRTE (AOSP MAX_PAYLOAD is 4 MiB; a
     * 256 KiB cap is comfortably compatible with every adbd since it also
     * bounds CNXN maxdata). */
    public static final int MAX_PAYLOAD = 256 * 1024;

    public final int cmd;
    public final int arg0;
    public final int arg1;
    public final byte[] payload;

    private AdbPacket(int cmd, int arg0, int arg1, byte[] payload) {
        this.cmd = cmd;
        this.arg0 = arg0;
        this.arg1 = arg1;
        this.payload = payload != null ? payload : new byte[0];
    }

    public static AdbPacket of(int cmd, int arg0, int arg1, byte[] payload) {
        return new AdbPacket(cmd, arg0, arg1, payload);
    }

    /** Packet with an empty payload (OKAY/CLSE). */
    public static AdbPacket of(int cmd, int arg0, int arg1) {
        return new AdbPacket(cmd, arg0, arg1, new byte[0]);
    }

    public static AdbPacket of(int cmd, int arg0, int arg1, String payload) {
        return new AdbPacket(cmd, arg0, arg1,
            payload != null ? payload.getBytes(StandardCharsets.UTF_8) : new byte[0]);
    }

    /** AOSP checksum(): sum of payload bytes as uint32. */
    public static int checksum(byte[] data, int len) {
        int sum = 0;
        for (int i = 0; i < len; i++) {
            sum += data[i] & 0xff;
        }
        return sum;
    }

    /** Serialize header (24 bytes) + payload. */
    public byte[] encode() {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(24 + payload.length);
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.putInt(cmd);
        buf.putInt(arg0);
        buf.putInt(arg1);
        buf.putInt(payload.length);
        buf.putInt(checksum(payload, payload.length));
        buf.putInt(cmd ^ 0xffffffff);
        buf.put(payload);
        return buf.array();
    }

    public void writeTo(OutputStream out) throws IOException {
        out.write(encode());
        out.flush();
    }

    /** Read one packet; returns null on orderly EOF at a header boundary. */
    public static AdbPacket readFrom(InputStream in) throws IOException {
        byte[] header = new byte[24];
        if (!readFully(in, header, 0, 24)) return null;
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(header);
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int cmd = buf.getInt();
        int arg0 = buf.getInt();
        int arg1 = buf.getInt();
        int dataLen = buf.getInt();
        int crc = buf.getInt();
        int magic = buf.getInt();
        if (cmd != (magic ^ 0xffffffff)) {
            throw new IOException(String.format(
                "adb: protocol fault: bad packet magic (cmd %#010x)", cmd));
        }
        if (dataLen < 0 || dataLen > MAX_PAYLOAD * 4) {
            throw new IOException("adb: protocol fault: oversized packet (" + dataLen + " bytes)");
        }
        byte[] payload = new byte[dataLen];
        if (!readFully(in, payload, 0, dataLen)) {
            throw new EOFException("EOF inside packet payload");
        }
        // Since A_VERSION_SKIP_CHECKSUM (0x01000001) sends are zero-checksum
        // and receivers skip verification (AOSP transport.cpp); tolerate both
        // zeroed and correct checksums, reject corrupt non-zero ones.
        int actual = checksum(payload, dataLen);
        if (crc != 0 && crc != actual) {
            throw new IOException("adb: protocol fault: packet checksum mismatch");
        }
        return new AdbPacket(cmd, arg0, arg1, payload);
    }

    private static boolean readFully(InputStream in, byte[] buf, int off, int len)
        throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(buf, off + got, len - got);
            if (n < 0) return false;
            if (n == 0) {
                // read() returning 0 on a blocking socket means the buffer was
                // zero-length; guard against a spin.
                if (len - got == 0) return true;
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                continue;
            }
            got += n;
        }
        return true;
    }

    @Override
    public String toString() {
        return String.format("AdbPacket(cmd=%#010x arg0=%d arg1=%d len=%d)",
            cmd, arg0, arg1, payload.length);
    }
}
