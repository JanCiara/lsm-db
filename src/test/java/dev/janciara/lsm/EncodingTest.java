package dev.janciara.lsm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EncodingTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---- varint -------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2, 127, 128, 300, 16383, 16384, 1L << 35, Long.MAX_VALUE, -1L})
    void varintRoundTrips(long v) throws IOException {
        var out = new ByteArrayOutputStream();
        Encoding.writeUVarLong(out, v);
        long back = Encoding.readUVarLong(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(v, back, "varint round-trip for " + v);
    }

    @Test
    void smallValuesUseOneByte() throws IOException {
        var out = new ByteArrayOutputStream();
        Encoding.writeUVarLong(out, 127);
        assertEquals(1, out.toByteArray().length, "127 powinno zajac 1 bajt");
    }

    @Test
    void unsignedMaxIsNotMistakenForEof() throws IOException {
        // -1L = unsigned 64-bit max. Wczesniej kolidowalo z sentinelem EOF.
        var out = new ByteArrayOutputStream();
        Encoding.writeUVarLong(out, -1L);
        assertEquals(-1L, Encoding.readUVarLong(new ByteArrayInputStream(out.toByteArray())));
    }

    // ---- record round-trip --------------------------------------------------

    @Test
    void valueRecordRoundTrips() {
        Record r = Record.value(b("user:1"), b("Janek"), 7);
        Record back = Encoding.deserialize(Encoding.serialize(r));
        assertArrayEquals(b("user:1"), back.key());
        assertArrayEquals(b("Janek"), back.value());
        assertEquals(7, back.seqNo());
        assertFalse(back.tombstone());
    }

    @Test
    void tombstoneRoundTrips() {
        Record r = Record.tombstone(b("user:1"), 9);
        Record back = Encoding.deserialize(Encoding.serialize(r));
        assertTrue(back.tombstone());
        assertEquals(0, back.value().length);
        assertEquals(9, back.seqNo());
    }

    @Test
    void emptyKeyAndValueRoundTrip() {
        Record r = Record.value(new byte[0], new byte[0], 0);
        Record back = Encoding.deserialize(Encoding.serialize(r));
        assertEquals(0, back.key().length);
        assertEquals(0, back.value().length);
        assertEquals(0, back.seqNo());
    }

    @Test
    void largeValueRoundTrips() {
        byte[] big = new byte[5000];
        new Random(1).nextBytes(big);
        Record r = Record.value(b("big"), big, 123_456_789L);
        Record back = Encoding.deserialize(Encoding.serialize(r));
        assertArrayEquals(big, back.value());
        assertEquals(123_456_789L, back.seqNo());
    }

    // ---- streaming (jak w WAL) ---------------------------------------------

    @Test
    void multipleRecordsStreamAndStopAtEof() throws IOException {
        var out = new ByteArrayOutputStream();
        for (int i = 0; i < 5; i++) {
            Encoding.writeRecord(out, Record.value(b("k" + i), b("v" + i), i));
        }
        var in = new ByteArrayInputStream(out.toByteArray());

        int count = 0;
        Record rec;
        while ((rec = Encoding.readRecord(in)) != null) {
            assertArrayEquals(b("k" + count), rec.key());
            assertArrayEquals(b("v" + count), rec.value());
            assertEquals(count, rec.seqNo());
            count++;
        }
        assertEquals(5, count);
        assertNull(Encoding.readRecord(in), "po EOF kolejny odczyt = null");
    }

    @Test
    void readRecordOnEmptyStreamReturnsNull() throws IOException {
        assertNull(Encoding.readRecord(new ByteArrayInputStream(new byte[0])));
    }
}
