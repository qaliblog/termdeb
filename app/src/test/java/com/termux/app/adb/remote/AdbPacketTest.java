package com.termux.app.adb.remote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Unit tests for the ADB wire framing (AOSP transport-local packet format). */
public class AdbPacketTest {

    @Test
    public void testCommandConstants() {
        // Verified against AOSP adb.h: "CNXN", "AUTH", "OPEN", "OKAY", "CLSE", "WRTE".
        assertEquals(0x434e584e, AdbPacket.A_CNXN);
        assertEquals(0x41555448, AdbPacket.A_AUTH);
        assertEquals(0x4f50454e, AdbPacket.A_OPEN);
        assertEquals(0x4f4b4159, AdbPacket.A_OKAY);
        assertEquals(0x434c5345, AdbPacket.A_CLSE);
        assertEquals(0x57525445, AdbPacket.A_WRTE);
    }

    @Test
    public void testEncodeDecodeRoundtrip() throws Exception {
        byte[] payload = "host::\0".getBytes("UTF-8");
        AdbPacket p = AdbPacket.of(AdbPacket.A_CNXN, AdbPacket.CONNECT_VERSION,
            AdbPacket.MAX_PAYLOAD, payload);

        byte[] wire = p.encode();
        assertEquals(24 + payload.length, wire.length);

        AdbPacket r = AdbPacket.readFrom(new ByteArrayInputStream(wire));
        assertEquals(AdbPacket.A_CNXN, r.cmd);
        assertEquals(AdbPacket.CONNECT_VERSION, r.arg0);
        assertEquals(AdbPacket.MAX_PAYLOAD, r.arg1);
        assertArrayEquals(payload, r.payload);
    }

    @Test
    public void testHeaderIsLittleEndian() {
        AdbPacket p = AdbPacket.of(AdbPacket.A_OKAY, 7, 9, new byte[0]);
        byte[] w = p.encode();
        // 'O','K','A','Y' in little-endian word order means the bytes appear
        // reversed: 0x4f4b4159 -> 59 41 4b 4f.
        assertEquals((byte) 0x59, w[0]);
        assertEquals((byte) 0x41, w[1]);
        assertEquals((byte) 0x4b, w[2]);
        assertEquals((byte) 0x4f, w[3]);
        // arg0=7 as LE uint32 at offset 4.
        assertEquals(7, w[4] & 0xff);
        assertEquals(0, w[5] & 0xff);
        // arg1=9 at offset 8.
        assertEquals(9, w[8] & 0xff);
    }

    @Test
    public void testChecksumMatchesAospSum() {
        byte[] data = new byte[]{1, 2, 3, (byte) 0xff};
        assertEquals(1 + 2 + 3 + 255, AdbPacket.checksum(data, data.length));
        assertEquals(0, AdbPacket.checksum(new byte[0], 0));
    }

    @Test
    public void testBadMagicIsRejected() throws Exception {
        AdbPacket p = AdbPacket.of(AdbPacket.A_OPEN, 1, 0, "shell:exit");
        byte[] w = p.encode();
        w[20] ^= 0x55; // corrupt magic (offset 20..23)
        try {
            AdbPacket.readFrom(new ByteArrayInputStream(w));
            fail("corrupt magic must be rejected");
        } catch (IOException e) {
            // expected
        }
    }

    @Test
    public void testBadChecksumIsRejected() throws Exception {
        AdbPacket p = AdbPacket.of(AdbPacket.A_OPEN, 1, 0, "shell:exit");
        byte[] w = p.encode();
        w[24] ^= 0x55; // corrupt first payload byte (checksum lives in header)
        try {
            AdbPacket.readFrom(new ByteArrayInputStream(w));
            fail("corrupt payload must be rejected by checksum");
        } catch (IOException e) {
            // expected
        }
    }

    @Test
    public void testEofAtHeaderBoundaryReturnsNull() throws Exception {
        assertNull(AdbPacket.readFrom(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    public void testEofInsidePayloadThrows() throws Exception {
        AdbPacket p = AdbPacket.of(AdbPacket.A_OPEN, 1, 0, "shell:exit");
        byte[] w = p.encode();
        byte[] truncated = new byte[24 + 3]; // header + partial payload
        System.arraycopy(w, 0, truncated, 0, truncated.length);
        try {
            AdbPacket.readFrom(new ByteArrayInputStream(truncated));
            fail("EOF inside payload must throw");
        } catch (IOException e) {
            // expected
        }
    }

    @Test
    public void testOversizedPacketIsRejected() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AdbPacket p = AdbPacket.of(AdbPacket.A_WRTE, 1, 2, new byte[0]);
        out.write(p.encode());
        // Patch data_len (offset 12, LE) to 0x00400000 (4 MiB) — beyond the
        // 1 MiB safety cap (MAX_PAYLOAD * 4).
        byte[] w = out.toByteArray();
        w[14] = 0x40;
        try {
            AdbPacket.readFrom(new ByteArrayInputStream(w));
            fail("oversized packet must be rejected");
        } catch (IOException e) {
            assertTrue("error must mention size", e.getMessage().contains("bytes"));
        }
    }
}
