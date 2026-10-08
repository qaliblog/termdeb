package com.termux.app.adb;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hex4 length-prefix framing helpers shared by the smart-protocol server and
 * the device-service streams (AOSP smart socket "hex4 payload" messages).
 */
final class SmartSocket {

    private SmartSocket() {
    }

    /** Read exactly n bytes. */
    static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(buf, off + got, len - got);
            if (n < 0) throw new EOFException("EOF after " + got + "/" + len + " bytes");
            got += n;
        }
    }

    /** Read a 4-hex-digit length followed by that many bytes. */
    static byte[] readHex4Payload(InputStream in) throws IOException {
        byte[] lenBuf = new byte[4];
        readFully(in, lenBuf, 0, 4);
        int len = parseHex4(lenBuf);
        byte[] buf = new byte[len];
        readFully(in, buf, 0, len);
        return buf;
    }

    static String readHex4String(InputStream in) throws IOException {
        return new String(readHex4Payload(in), java.nio.charset.StandardCharsets.UTF_8);
    }

    static int parseHex4(byte[] buf) {
        int len = 0;
        for (int i = 0; i < 4; i++) {
            int v = Character.digit((char) (buf[i] & 0xff), 16);
            if (v < 0) throw new NumberFormatException("bad hex4 length prefix");
            len = (len << 4) | v;
        }
        return len;
    }

    static void writeHex4Payload(OutputStream out, String payload) throws IOException {
        byte[] data = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeHex4Payload(out, data);
    }

    static void writeHex4Payload(OutputStream out, byte[] data) throws IOException {
        if (data.length > 0xFFFF) throw new IOException("payload too large for hex4");
        String header = String.format("%04x", data.length);
        out.write(header.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }

    static void writeOkay(OutputStream out) throws IOException {
        out.write('O');
        out.write('K');
        out.write('A');
        out.write('Y');
        out.flush();
    }

    /** FAIL + hex4 length + reason. */
    static void writeFail(OutputStream out, String reason) throws IOException {
        out.write('F');
        out.write('A');
        out.write('I');
        out.write('L');
        byte[] data = reason.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.write(String.format("%04x", data.length).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }

    /** The server's end-of-query marker ("0000"). */
    static void writeQueryTail(OutputStream out) throws IOException {
        out.write('0');
        out.write('0');
        out.write('0');
        out.write('0');
        out.flush();
    }
}
