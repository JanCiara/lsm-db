package dev.janciara.lsm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SSTableTest {

    /**
     * Every opened table holds a file channel until {@code close()}. These tests create them in
     * bulk, so we collect them here and close them after each test instead of sprinkling
     * try-with-resources everywhere.
     */
    private final List<SSTable> opened = new ArrayList<>();

    @AfterEach
    void closeOpenedTables() throws IOException {
        for (SSTable t : opened) {
            t.close(); // idempotent, so tables deleted inside a test are safe here too
        }
    }

    private SSTable track(SSTable table) {
        opened.add(table);
        return table;
    }

    private SSTable open(Path file) throws IOException {
        return track(SSTable.open(file));
    }

    private Optional<SSTable> compact(Path target, List<SSTable> inputs, boolean dropTombstones)
            throws IOException {
        Optional<SSTable> merged = SSTable.compact(target, inputs, dropTombstones);
        merged.ifPresent(this::track);
        return merged;
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Writes through a memtable so the input is sorted exactly as in a real flush. */
    private SSTable writeTable(Path file, Record... records) throws IOException {
        var mt = new MemTable();
        for (Record r : records) mt.put(r);
        return track(SSTable.write(file, mt.snapshot()));
    }

    @Test
    void writtenRecordsCanBeFoundByKey(@TempDir Path dir) throws IOException {
        SSTable t = writeTable(dir.resolve("t.sst"),
                Record.value(b("b"), b("2"), 1),
                Record.value(b("a"), b("1"), 0),
                Record.value(b("c"), b("3"), 2));

        assertArrayEquals(b("1"), t.get(b("a")).orElseThrow().value());
        assertArrayEquals(b("2"), t.get(b("b")).orElseThrow().value());
        assertArrayEquals(b("3"), t.get(b("c")).orElseThrow().value());
    }

    @Test
    void missingKeyInsideAndOutsideRangeReturnsEmpty(@TempDir Path dir) throws IOException {
        SSTable t = writeTable(dir.resolve("t.sst"),
                Record.value(b("b"), b("2"), 0),
                Record.value(b("d"), b("4"), 1));

        assertTrue(t.get(b("c")).isEmpty(), "a hole in the middle of the range");
        assertTrue(t.get(b("a")).isEmpty(), "before minKey");
        assertTrue(t.get(b("z")).isEmpty(), "past maxKey");
        assertFalse(t.mightContain(b("z")), "rejected by the key range");
        assertFalse(t.mightContain(b("c")), "a hole inside the range is only caught by the Bloom filter");
        assertTrue(t.mightContain(b("b")), "an existing key is never rejected");
    }

    @Test
    void tombstoneSurvivesRoundTrip(@TempDir Path dir) throws IOException {
        SSTable t = writeTable(dir.resolve("t.sst"), Record.tombstone(b("k"), 7));

        Record r = t.get(b("k")).orElseThrow();
        assertTrue(r.tombstone(), "the SSTable hands the record back as-is — translating is LsmStore's job");
        assertEquals(7, r.seqNo());
    }

    @Test
    void metadataFromFooterIsReadBackOnOpen(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("t.sst");
        writeTable(file,
                Record.value(b("a"), b("1"), 3),
                Record.value(b("m"), b("2"), 9),
                Record.value(b("z"), b("3"), 5));

        SSTable reopened = open(file);
        assertEquals(3, reopened.entryCount());
        assertEquals(9, reopened.maxSeqNo(), "the footer remembers the highest seqNo, not the last written");
        assertArrayEquals(b("a"), reopened.minKey());
        assertArrayEquals(b("z"), reopened.maxKey());
        assertArrayEquals(b("2"), reopened.get(b("m")).orElseThrow().value());
    }

    @Test
    void readAllReturnsEveryRecordInKeyOrder(@TempDir Path dir) throws IOException {
        SSTable t = writeTable(dir.resolve("t.sst"),
                Record.value(b("c"), b("3"), 2),
                Record.value(b("a"), b("1"), 0),
                Record.tombstone(b("b"), 1));

        List<Record> all = t.readAll();
        assertEquals(3, all.size());
        assertArrayEquals(b("a"), all.get(0).key());
        assertArrayEquals(b("b"), all.get(1).key());
        assertArrayEquals(b("c"), all.get(2).key());
        assertTrue(all.get(1).tombstone());
    }

    @Test
    void keysAreOrderedUnsigned(@TempDir Path dir) throws IOException {
        byte[] hi = {(byte) 0x80};
        byte[] lo = {(byte) 0x7F};
        SSTable t = writeTable(dir.resolve("t.sst"),
                Record.value(hi, b("hi"), 1),
                Record.value(lo, b("lo"), 0));

        assertArrayEquals(lo, t.minKey(), "0x7F < 0x80 in unsigned order");
        assertArrayEquals(hi, t.maxKey());
        assertArrayEquals(b("hi"), t.get(hi).orElseThrow().value());
    }

    @Test
    void emptyKeyAndValueRoundTrip(@TempDir Path dir) throws IOException {
        SSTable t = writeTable(dir.resolve("t.sst"),
                Record.value(new byte[0], new byte[0], 0),
                Record.value(b("k"), b("v"), 1));

        assertArrayEquals(new byte[0], t.get(new byte[0]).orElseThrow().value());
        assertArrayEquals(new byte[0], t.minKey());
    }

    @Test
    void unsortedInputIsRejected(@TempDir Path dir) {
        List<Record> unsorted = List.of(
                Record.value(b("b"), b("2"), 0),
                Record.value(b("a"), b("1"), 1));

        assertThrows(IllegalArgumentException.class,
                () -> SSTable.write(dir.resolve("t.sst"), unsorted));
    }

    @Test
    void duplicateKeysAreRejected(@TempDir Path dir) {
        List<Record> duplicates = List.of(
                Record.value(b("a"), b("1"), 0),
                Record.value(b("a"), b("2"), 1));

        assertThrows(IllegalArgumentException.class,
                () -> SSTable.write(dir.resolve("t.sst"), duplicates));
    }

    @Test
    void emptyInputIsRejected(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class,
                () -> SSTable.write(dir.resolve("t.sst"), List.of()));
    }

    @Test
    void writeLeavesNoTempFileBehind(@TempDir Path dir) throws IOException {
        writeTable(dir.resolve("t.sst"), Record.value(b("k"), b("v"), 0));

        var names = new ArrayList<String>();
        try (var ls = Files.list(dir)) {
            ls.forEach(p -> names.add(p.getFileName().toString()));
        }
        assertEquals(List.of("t.sst"), names, "after the atomic swap only the target file remains");
    }

    @Test
    void truncatedFileIsRejectedOnOpen(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("t.sst");
        writeTable(file, Record.value(b("k"), b("v"), 0));

        byte[] full = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(full, full.length - 3)); // torn tail = no trailing magic

        assertThrows(IOException.class, () -> SSTable.open(file));
    }

    @Test
    void fileWithWrongMagicIsRejectedOnOpen(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("t.sst");
        writeTable(file, Record.value(b("k"), b("v"), 0));

        byte[] full = Files.readAllBytes(file);
        full[0] = 'X';
        Files.write(file, full);

        assertThrows(IOException.class, () -> SSTable.open(file));
    }

    @Test
    void tooShortFileIsRejectedOnOpen(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("t.sst");
        Files.write(file, new byte[] {'L', 'S', 'M', 'T'});

        assertThrows(IOException.class, () -> SSTable.open(file));
    }

    @Test
    void cursorWalksRecordsInKeyOrderAndStops(@TempDir Path dir) throws IOException {
        SSTable t = writeTable(dir.resolve("t.sst"),
                Record.value(b("b"), b("2"), 1),
                Record.value(b("a"), b("1"), 0));

        try (SSTable.Cursor cursor = t.cursor()) {
            assertArrayEquals(b("a"), cursor.peek().key());
            assertArrayEquals(b("a"), cursor.peek().key(), "peek does not move the cursor");
            cursor.advance();
            assertArrayEquals(b("b"), cursor.peek().key());
            cursor.advance();
            assertNull(cursor.peek(), "null means the end of the table");
        }
    }

    @Test
    void abandonedWriterLeavesNoTempFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("t.sst");
        try (SSTable.Writer writer = SSTable.writer(file)) {
            writer.add(Record.value(b("a"), b("1"), 0));
            // no finish() — simulates an exception in the middle of a merge
        }

        assertFalse(Files.exists(file), "the target file is never created");
        assertFalse(Files.exists(dir.resolve("t.sst.tmp")), "the writer cleaned up after itself");
    }

    // ---- M3: merging -------------------------------------------------------

    @Test
    void compactKeepsNewestVersionOfEachKey(@TempDir Path dir) throws IOException {
        SSTable older = writeTable(dir.resolve("0.sst"),
                Record.value(b("a"), b("old-a"), 0),
                Record.value(b("b"), b("old-b"), 1));
        SSTable newer = writeTable(dir.resolve("1.sst"),
                Record.value(b("b"), b("new-b"), 2),
                Record.value(b("c"), b("new-c"), 3));

        SSTable merged = compact(dir.resolve("2.sst"), List.of(older, newer), true).orElseThrow();

        List<Record> all = merged.readAll();
        assertEquals(3, all.size(), "the shared key survives once");
        assertArrayEquals(b("old-a"), all.get(0).value());
        assertArrayEquals(b("new-b"), all.get(1).value(), "the newer table's version wins");
        assertArrayEquals(b("new-c"), all.get(2).value());
        assertEquals(3, merged.maxSeqNo());
    }

    @Test
    void compactInterleavesKeysFromManyTables(@TempDir Path dir) throws IOException {
        SSTable t0 = writeTable(dir.resolve("0.sst"), Record.value(b("a"), b("1"), 0),
                Record.value(b("d"), b("4"), 1));
        SSTable t1 = writeTable(dir.resolve("1.sst"), Record.value(b("b"), b("2"), 2));
        SSTable t2 = writeTable(dir.resolve("2.sst"), Record.value(b("c"), b("3"), 3),
                Record.value(b("e"), b("5"), 4));

        SSTable merged = compact(dir.resolve("3.sst"), List.of(t0, t1, t2), true).orElseThrow();

        List<byte[]> keys = merged.readAll().stream().map(Record::key).toList();
        assertEquals(5, keys.size());
        assertArrayEquals(b("a"), keys.get(0));
        assertArrayEquals(b("b"), keys.get(1));
        assertArrayEquals(b("c"), keys.get(2));
        assertArrayEquals(b("d"), keys.get(3));
        assertArrayEquals(b("e"), keys.get(4));
    }

    @Test
    void compactDropsTombstonesWhenMergingEverything(@TempDir Path dir) throws IOException {
        SSTable older = writeTable(dir.resolve("0.sst"),
                Record.value(b("survives"), b("v"), 0),
                Record.value(b("vanishes"), b("v"), 1));
        SSTable newer = writeTable(dir.resolve("1.sst"), Record.tombstone(b("vanishes"), 2));

        SSTable merged = compact(dir.resolve("2.sst"), List.of(older, newer), true).orElseThrow();

        List<Record> all = merged.readAll();
        assertEquals(1, all.size(), "both the value and the tombstone hiding it are gone");
        assertArrayEquals(b("survives"), all.get(0).key());
    }

    @Test
    void compactKeepsTombstonesWhenOlderTablesRemain(@TempDir Path dir) throws IOException {
        SSTable older = writeTable(dir.resolve("0.sst"), Record.value(b("k"), b("v"), 0));
        SSTable newer = writeTable(dir.resolve("1.sst"), Record.tombstone(b("k"), 1));

        // dropTombstones=false: an older table may lie underneath, still guarded by this tombstone
        SSTable merged = compact(dir.resolve("2.sst"), List.of(older, newer), false).orElseThrow();

        List<Record> all = merged.readAll();
        assertEquals(1, all.size());
        assertTrue(all.get(0).tombstone(), "the tombstone was copied through, not discarded");
    }

    @Test
    void compactProducesNoFileWhenEverythingWasDeleted(@TempDir Path dir) throws IOException {
        SSTable older = writeTable(dir.resolve("0.sst"), Record.value(b("k"), b("v"), 0));
        SSTable newer = writeTable(dir.resolve("1.sst"), Record.tombstone(b("k"), 1));

        Path target = dir.resolve("2.sst");
        assertTrue(compact(target, List.of(older, newer), true).isEmpty());
        assertFalse(Files.exists(target), "an empty table leaves no file behind");
        assertFalse(Files.exists(dir.resolve("2.sst.tmp")));
    }

    // ---- M4: block index and Bloom filter -----------------------------------

    /** A table clearly bigger than one block: 200 records of ~120 B is ~24 KiB. */
    private SSTable multiBlockTable(Path file) throws IOException {
        var mt = new MemTable();
        for (int i = 0; i < 200; i++) {
            mt.put(Record.value(b(String.format("key:%04d", i)), b("v".repeat(120)), i));
        }
        return track(SSTable.write(file, mt.snapshot()));
    }

    @Test
    void largeTableIsSplitIntoSeveralBlocks(@TempDir Path dir) throws IOException {
        SSTable t = multiBlockTable(dir.resolve("t.sst"));

        assertTrue(t.blockCount() > 1, "24 KiB of data does not fit in a single 4 KiB block");
        assertEquals(t.blockCount(), open(dir.resolve("t.sst")).blockCount(),
                "the index was restored from the footer unchanged");
    }

    @Test
    void everyKeyIsFoundThroughTheIndex(@TempDir Path dir) throws IOException {
        multiBlockTable(dir.resolve("t.sst"));
        SSTable t = open(dir.resolve("t.sst")); // read via the on-disk index, not the writer's memory

        for (int i = 0; i < 200; i++) {
            String key = String.format("key:%04d", i);
            assertArrayEquals(b("v".repeat(120)), t.get(b(key)).orElseThrow().value(), key);
        }
        assertTrue(t.get(b("key:0200")).isEmpty(), "past maxKey");
        assertTrue(t.get(b("key:")).isEmpty(), "before minKey");
    }

    @Test
    void missingKeyBetweenBlocksIsNotFound(@TempDir Path dir) throws IOException {
        var mt = new MemTable();
        for (int i = 0; i < 200; i += 2) { // even numbers only
            mt.put(Record.value(b(String.format("key:%04d", i)), b("v".repeat(120)), i));
        }
        SSTable t = open(track(SSTable.write(dir.resolve("t.sst"), mt.snapshot())).path());

        for (int i = 1; i < 200; i += 2) { // ask for the odd ones
            String key = String.format("key:%04d", i);
            assertTrue(t.get(b(key)).isEmpty(), key + " does not exist");
        }
    }

    @Test
    void indexSurvivesCompaction(@TempDir Path dir) throws IOException {
        SSTable older = multiBlockTable(dir.resolve("0.sst"));
        var mt = new MemTable();
        mt.put(Record.value(b("key:0000"), b("overwritten"), 1000));
        SSTable newer = track(SSTable.write(dir.resolve("1.sst"), mt.snapshot()));

        SSTable merged = compact(dir.resolve("2.sst"), List.of(older, newer), true).orElseThrow();
        SSTable reopened = open(merged.path());

        assertTrue(reopened.blockCount() > 1, "the merged table has an index too");
        assertEquals(200, reopened.entryCount());
        assertArrayEquals(b("overwritten"), reopened.get(b("key:0000")).orElseThrow().value());
        assertArrayEquals(b("v".repeat(120)), reopened.get(b("key:0199")).orElseThrow().value());
    }

    @Test
    void bloomRejectsMissingKeysWithoutTouchingDisk(@TempDir Path dir) throws IOException {
        multiBlockTable(dir.resolve("t.sst"));
        SSTable t = open(dir.resolve("t.sst"));

        int survived = 0;
        for (int i = 0; i < 1000; i++) {
            // Keys inside minKey..maxKey, so the range will not reject them — the filter does.
            if (t.mightContain(b(String.format("key:%04d-x", i)))) survived++;
        }
        assertTrue(survived < 100, "the filter let through " + survived + "/1000 nonexistent keys");
    }

    @Test
    void oldFormatVersionIsRejected(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("t.sst");
        writeTable(file, Record.value(b("k"), b("v"), 0));

        byte[] raw = Files.readAllBytes(file);
        raw[SSTable.MAGIC.length] = 1; // an M2 file: data with no index and no filter
        Files.write(file, raw);

        IOException e = assertThrows(IOException.class, () -> SSTable.open(file));
        assertTrue(e.getMessage().contains("version"), e.getMessage());
    }

    @Test
    void compactedTableCanDeleteItsSources(@TempDir Path dir) throws IOException {
        SSTable older = writeTable(dir.resolve("0.sst"), Record.value(b("a"), b("1"), 0));
        SSTable newer = writeTable(dir.resolve("1.sst"), Record.value(b("b"), b("2"), 1));

        SSTable merged = compact(dir.resolve("2.sst"), List.of(older, newer), true).orElseThrow();
        // delete() closes the channel first — otherwise the merge cursors would still hold the file.
        older.delete();
        newer.delete();

        assertFalse(Files.exists(dir.resolve("0.sst")));
        assertArrayEquals(b("2"), merged.get(b("b")).orElseThrow().value());
    }
}
