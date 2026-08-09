package dev.janciara.lsm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class MemTableTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void putThenGetReturnsRecord() {
        var mt = new MemTable();
        mt.put(Record.value(b("k"), b("v"), 1));

        Optional<Record> got = mt.get(b("k"));
        assertTrue(got.isPresent());
        assertArrayEquals(b("v"), got.get().value());
    }

    @Test
    void overwriteKeepsLatest() {
        var mt = new MemTable();
        mt.put(Record.value(b("k"), b("old"), 1));
        mt.put(Record.value(b("k"), b("new"), 2));

        assertEquals(1, mt.size());
        assertArrayEquals(b("new"), mt.get(b("k")).orElseThrow().value());
    }

    @Test
    void missingKeyReturnsEmpty() {
        var mt = new MemTable();
        assertTrue(mt.get(b("nope")).isEmpty());
        assertTrue(mt.isEmpty());
    }

    @Test
    void tombstoneIsStoredAndReturnedAsIs() {
        var mt = new MemTable();
        mt.put(Record.tombstone(b("k"), 5));

        Record r = mt.get(b("k")).orElseThrow();
        assertTrue(r.tombstone(), "the memtable hands back the whole record, tombstone included");
    }

    @Test
    void snapshotIsSortedUnsignedByKey() {
        var mt = new MemTable();
        // 0x80 > 0x7F in unsigned order (signed comparison would put them the other way round).
        byte[] hi = {(byte) 0x80};
        byte[] lo = {(byte) 0x7F};
        mt.put(Record.value(hi, b("hi"), 2));
        mt.put(Record.value(lo, b("lo"), 1));

        List<Record> ordered = List.copyOf(mt.snapshot());
        assertArrayEquals(lo, ordered.get(0).key(), "0x7F should come first");
        assertArrayEquals(hi, ordered.get(1).key(), "0x80 should come second (unsigned)");
        assertFalse(mt.isEmpty());
    }
}
