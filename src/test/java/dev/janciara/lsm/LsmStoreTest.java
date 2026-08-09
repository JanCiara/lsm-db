package dev.janciara.lsm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LsmStoreTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static LsmStore.Options options() {
        return LsmStore.Options.defaults();
    }

    private static long walSize(Path dir) throws IOException {
        return Files.size(dir.resolve("wal.log"));
    }

    /** Table files actually present on disk — to check that merging cleans up after itself. */
    private static List<Path> sstFiles(Path dir) throws IOException {
        try (var ls = Files.list(dir)) {
            return ls.filter(p -> p.getFileName().toString().endsWith(".sst")).sorted().toList();
        }
    }

    @Test
    void putThenGet(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("user:1"), b("Janek"));
            assertArrayEquals(b("Janek"), db.get(b("user:1")).orElseThrow());
        }
    }

    @Test
    void missingKeyReturnsEmpty(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            assertTrue(db.get(b("nope")).isEmpty());
        }
    }

    @Test
    void deleteHidesKey(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("v"));
            db.delete(b("k"));
            assertTrue(db.get(b("k")).isEmpty());
        }
    }

    @Test
    void overwriteReturnsLatestValue(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("old"));
            db.put(b("k"), b("new"));
            assertArrayEquals(b("new"), db.get(b("k")).orElseThrow());
        }
    }

    @Test
    void emptyKeyAndValueRoundTrip(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(new byte[0], new byte[0]);
            Optional<byte[]> got = db.get(new byte[0]);
            assertTrue(got.isPresent());
            assertArrayEquals(new byte[0], got.get());
        }
    }

    @Test
    void mutatingCallerArrayDoesNotCorruptStore(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            byte[] key = b("k");
            byte[] value = b("v0");
            db.put(key, value);
            value[1] = (byte) '9';           // caller mutates after the write
            assertArrayEquals(b("v0"), db.get(b("k")).orElseThrow(), "the store makes a defensive copy");
        }
    }

    @Test
    void valuesPersistAcrossReopen(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("a"), b("1"));
            db.put(b("b"), b("2"));
        }
        // A new process/open — state rebuilt purely from the WAL.
        try (LsmStore db = LsmStore.open(dir)) {
            assertArrayEquals(b("1"), db.get(b("a")).orElseThrow());
            assertArrayEquals(b("2"), db.get(b("b")).orElseThrow());
        }
    }

    @Test
    void deletePersistsAcrossReopen(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("v"));
            db.delete(b("k"));
        }
        try (LsmStore db = LsmStore.open(dir)) {
            assertTrue(db.get(b("k")).isEmpty(), "the tombstone survived replay");
        }
    }

    @Test
    void overwriteAfterReopenWins(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("old"));
        }
        // After replay seqNo keeps growing, so the new write overwrites the old one.
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("new"));
            assertArrayEquals(b("new"), db.get(b("k")).orElseThrow());
        }
        try (LsmStore db = LsmStore.open(dir)) {
            assertArrayEquals(b("new"), db.get(b("k")).orElseThrow());
            assertFalse(db.get(b("k")).isEmpty());
        }
    }

    // ---- M2: flushing to an SSTable ----------------------------------------

    @Test
    void flushMovesDataFromMemtableToSSTable(@TempDir Path dir) throws IOException {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("a"), b("1"));
            assertTrue(walSize(dir) > 0);

            db.flush();
            assertEquals(1, db.sstableCount());
            assertEquals(0, walSize(dir), "after a flush the log is redundant and gets cleared");
            assertArrayEquals(b("1"), db.get(b("a")).orElseThrow(), "the read descends into the SSTable");
        }
    }

    @Test
    void flushWithEmptyMemtableIsNoOp(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.flush();
            db.flush();
            assertEquals(0, db.sstableCount(), "we do not create empty files");
        }
    }

    @Test
    void flushedDataSurvivesReopen(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("a"), b("1"));
            db.put(b("b"), b("2"));
            db.flush();
        }
        try (LsmStore db = LsmStore.open(dir)) {
            assertEquals(1, db.sstableCount(), "the table was found by its file name");
            assertArrayEquals(b("1"), db.get(b("a")).orElseThrow());
            assertArrayEquals(b("2"), db.get(b("b")).orElseThrow());
        }
    }

    @Test
    void newerValueShadowsOlderSSTable(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("old"));
            db.flush();

            db.put(b("k"), b("new"));
            assertArrayEquals(b("new"), db.get(b("k")).orElseThrow(), "the memtable beats the SSTable");

            db.flush();
            assertEquals(2, db.sstableCount());
            assertArrayEquals(b("new"), db.get(b("k")).orElseThrow(), "the newer table beats the older one");
        }
    }

    @Test
    void tombstoneHidesValueLivingInOlderSSTable(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("v"));
            db.flush();

            db.delete(b("k"));
            assertTrue(db.get(b("k")).isEmpty(), "a tombstone in the memtable hides the SSTable");
            db.flush();
        }
        // The tombstone survived as an ordinary record in the newer table — value still hidden.
        try (LsmStore db = LsmStore.open(dir)) {
            assertEquals(2, db.sstableCount());
            assertTrue(db.get(b("k")).isEmpty());
        }
    }

    @Test
    void readFallsThroughSeveralSSTables(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("a"), b("1"));
            db.flush();
            db.put(b("b"), b("2"));
            db.flush();
            db.put(b("c"), b("3"));

            assertEquals(2, db.sstableCount());
            assertArrayEquals(b("1"), db.get(b("a")).orElseThrow(), "the oldest table");
            assertArrayEquals(b("2"), db.get(b("b")).orElseThrow(), "a newer table");
            assertArrayEquals(b("3"), db.get(b("c")).orElseThrow(), "still in the memtable");
            assertTrue(db.get(b("nope")).isEmpty(), "a miss walks everything and ends up empty");
        }
    }

    @Test
    void memtableIsFlushedAutomaticallyAfterThreshold(@TempDir Path dir) throws IOException {
        // A threshold sized to fit a handful of entries, not a few thousand.
        try (LsmStore db = LsmStore.open(dir, options().withFlushThresholdBytes(200))) {
            db.put(b("k0"), b("v0"));
            assertEquals(0, db.sstableCount());

            for (int i = 1; i < 10; i++) {
                db.put(b("k" + i), b("v" + i));
            }
            assertTrue(db.sstableCount() > 0, "the threshold forced a flush without a manual flush()");
            assertTrue(walSize(dir) < 200, "the log restarts from zero after every flush");

            for (int i = 0; i < 10; i++) {
                assertArrayEquals(b("v" + i), db.get(b("k" + i)).orElseThrow(), "key k" + i);
            }
        }
    }

    @Test
    void seqNoContinuesAcrossFlushAndReopen(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("old"));
            db.flush(); // seqNo now lives only in the SSTable footer — the WAL is empty
        }
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("new"));
            db.flush();
        }
        try (LsmStore db = LsmStore.open(dir)) {
            assertArrayEquals(b("new"), db.get(b("k")).orElseThrow(),
                    "the counter resumed from the footer, so the new write landed in a newer table");
        }
    }

    @Test
    void walIsReplayedOnTopOfSSTables(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("flushed"));
            db.flush();
            db.put(b("k"), b("only-in-wal")); // no flush — closing leaves this in the log
        }
        try (LsmStore db = LsmStore.open(dir)) {
            assertArrayEquals(b("only-in-wal"), db.get(b("k")).orElseThrow(),
                    "replay returns it to the memtable, which beats the SSTable");
        }
    }

    @Test
    void leftoverTempFileIsRemovedOnOpen(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Path junk = dir.resolve("sst-000000.sst.tmp"); // an unfinished flush from before a crash
        Files.writeString(junk, "half a table");

        try (LsmStore db = LsmStore.open(dir)) {
            assertEquals(0, db.sstableCount());
        }
        assertFalse(Files.exists(junk));
    }

    // ---- M3: merging tables ------------------------------------------------

    @Test
    void compactionRunsAutomaticallyAfterTriggerCount(@TempDir Path dir) throws IOException {
        try (LsmStore db = LsmStore.open(dir, options().withCompactionTrigger(3))) {
            db.put(b("a"), b("1"));
            db.flush();
            db.put(b("b"), b("2"));
            db.flush();
            assertEquals(2, db.sstableCount(), "still below the threshold");

            db.put(b("c"), b("3"));
            db.flush();
            assertEquals(1, db.sstableCount(), "the third flush triggered a merge into one table");

            assertArrayEquals(b("1"), db.get(b("a")).orElseThrow());
            assertArrayEquals(b("2"), db.get(b("b")).orElseThrow());
            assertArrayEquals(b("3"), db.get(b("c")).orElseThrow());
        }
        assertEquals(1, sstFiles(dir).size(), "the old files were deleted from disk");
    }

    @Test
    void compactionReclaimsSpaceAfterOverwrites(@TempDir Path dir) throws IOException {
        try (LsmStore db = LsmStore.open(dir)) {
            for (int i = 0; i < 5; i++) {
                db.put(b("k"), b("version-" + i)); // the same key, five times over
                db.flush();
            }
            db.compact();
            assertArrayEquals(b("version-4"), db.get(b("k")).orElseThrow());
        }

        List<Path> files = sstFiles(dir);
        assertEquals(1, files.size());
        try (SSTable table = SSTable.open(files.get(0))) {
            assertEquals(1, table.entryCount(),
                    "five versions of the key reduced to one — only this reclaims space");
        }
    }

    @Test
    void compactionRemovesDeletedKeysFromDisk(@TempDir Path dir) throws IOException {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("stays"), b("v"));
            db.put(b("goes"), b("v"));
            db.flush();

            db.delete(b("goes"));
            db.flush();
            assertEquals(2, db.sstableCount());

            db.compact();
            assertTrue(db.get(b("goes")).isEmpty());
            assertArrayEquals(b("v"), db.get(b("stays")).orElseThrow());
        }

        try (SSTable table = SSTable.open(sstFiles(dir).get(0))) {
            List<Record> onDisk = table.readAll();
            assertEquals(1, onDisk.size(), "neither the value nor the tombstone takes up space now");
            assertArrayEquals(b("stays"), onDisk.get(0).key());
        }
    }

    @Test
    void compactingAnEntirelyDeletedStoreLeavesNoTables(@TempDir Path dir) throws IOException {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("v"));
            db.flush();
            db.delete(b("k"));
            db.flush();

            db.compact();
            assertEquals(0, db.sstableCount(), "there was nothing left to write");
            assertTrue(db.get(b("k")).isEmpty());
        }
        assertTrue(sstFiles(dir).isEmpty());

        try (LsmStore db = LsmStore.open(dir)) {
            assertTrue(db.get(b("k")).isEmpty(), "the key does not come back after reopening");
        }
    }

    @Test
    void compactedDataSurvivesReopen(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("a"), b("old"));
            db.flush();
            db.put(b("a"), b("new"));
            db.put(b("b"), b("2"));
            db.flush();
            db.compact();
        }
        try (LsmStore db = LsmStore.open(dir)) {
            assertEquals(1, db.sstableCount());
            assertArrayEquals(b("new"), db.get(b("a")).orElseThrow());
            assertArrayEquals(b("2"), db.get(b("b")).orElseThrow());
        }
    }

    @Test
    void writesAfterCompactionStillWin(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("old"));
            db.flush();
            db.put(b("k"), b("middle"));
            db.flush();
            db.compact();

            db.put(b("k"), b("new")); // seqNo must be higher than anything in the merged table
            db.flush();
            assertArrayEquals(b("new"), db.get(b("k")).orElseThrow());
        }
        try (LsmStore db = LsmStore.open(dir)) {
            assertArrayEquals(b("new"), db.get(b("k")).orElseThrow());
        }
    }

    @Test
    void manualCompactIsNoOpBelowTwoTables(@TempDir Path dir) throws IOException {
        try (LsmStore db = LsmStore.open(dir)) {
            db.compact();
            assertEquals(0, db.sstableCount());

            db.put(b("k"), b("v"));
            db.flush();
            db.compact();
            assertEquals(1, db.sstableCount(), "a single table has nothing to merge with");
        }
        assertEquals(1, sstFiles(dir).size(), "no rewritten file sitting next to the old one");
    }

    @Test
    void mixedWorkloadStaysCorrectAcrossFlushesAndCompaction(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir, options().withCompactionTrigger(3))) {
            for (int i = 0; i < 30; i++) {
                db.put(b("k" + i), b("v" + i));
                if (i % 5 == 4) db.flush();
            }
            for (int i = 0; i < 30; i += 3) {
                db.put(b("k" + i), b("overwritten" + i)); // every third one overwritten
            }
            db.flush();
            for (int i = 0; i < 30; i += 7) {
                db.delete(b("k" + i)); // every seventh one deleted
            }
            db.flush();
            db.compact();

            assertEquals(1, db.sstableCount());
            for (int i = 0; i < 30; i++) {
                if (i % 7 == 0) {
                    assertTrue(db.get(b("k" + i)).isEmpty(), "k" + i + " deleted");
                } else if (i % 3 == 0) {
                    assertArrayEquals(b("overwritten" + i), db.get(b("k" + i)).orElseThrow(), "k" + i);
                } else {
                    assertArrayEquals(b("v" + i), db.get(b("k" + i)).orElseThrow(), "k" + i);
                }
            }
        }
    }

    /** State after two flushes: sst-000000 with a value, sst-000001 with the tombstone hiding it. */
    private static void valueThenTombstoneInSeparateTables(Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("v"));
            db.flush();
            db.delete(b("k"));
            db.flush();
        }
    }

    @Test
    void crashBetweenMergeAndCleanupKeepsStoreCorrect(@TempDir Path dir) throws IOException {
        valueThenTombstoneInSeparateTables(dir);
        // The merge yields an empty result, so no new file appears. We simulate a crash halfway
        // through cleanup: compact() deletes oldest-first, so only sst-000000 had time to go.
        Files.delete(dir.resolve("sst-000000.sst"));

        try (LsmStore db = LsmStore.open(dir)) {
            assertTrue(db.get(b("k")).isEmpty(), "the surviving tombstone still keeps the key deleted");
        }
    }

    @Test
    void deletingNewerTableFirstWouldResurrectDeletedKey(@TempDir Path dir) throws IOException {
        valueThenTombstoneInSeparateTables(dir);
        // Same situation, but cleanup started from the newer table — the opposite of compact().
        Files.delete(dir.resolve("sst-000001.sst"));

        try (LsmStore db = LsmStore.open(dir)) {
            assertArrayEquals(b("v"), db.get(b("k")).orElseThrow(),
                    "without the tombstone the old value returns — hence compact() deletes oldest-first");
        }
    }

    // ---- M5: robustness and settings ---------------------------------------

    @Test
    void storeOpensAfterCrashInTheMiddleOfAWrite(@TempDir Path dir) throws IOException {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("a"), b("1"));
            db.put(b("b"), b("2"));
        }
        // A crash halfway through the third write: the last record in the log is incomplete.
        Path log = dir.resolve("wal.log");
        byte[] full = Files.readAllBytes(log);
        Files.write(log, java.util.Arrays.copyOf(full, full.length - 2));

        try (LsmStore db = LsmStore.open(dir)) {
            assertArrayEquals(b("1"), db.get(b("a")).orElseThrow(), "acknowledged writes survived");
            assertTrue(db.get(b("b")).isEmpty(), "the unfinished write was never acknowledged");

            db.put(b("c"), b("3")); // the store must remain usable
            assertArrayEquals(b("3"), db.get(b("c")).orElseThrow());
        }
        try (LsmStore db = LsmStore.open(dir)) {
            assertArrayEquals(b("3"), db.get(b("c")).orElseThrow());
        }
    }

    @Test
    void bufferedDurabilityKeepsSameSemanticsForCleanClose(@TempDir Path dir) {
        LsmStore.Options buffered = options().withDurability(Wal.Durability.OS_BUFFERED);
        try (LsmStore db = LsmStore.open(dir, buffered)) {
            db.put(b("k"), b("v"));
            db.delete(b("removed"));
        }
        try (LsmStore db = LsmStore.open(dir, buffered)) {
            assertArrayEquals(b("v"), db.get(b("k")).orElseThrow());
            assertTrue(db.get(b("removed")).isEmpty());
        }
    }

    @Test
    void optionsRejectNonsense() {
        assertThrows(IllegalArgumentException.class, () -> options().withFlushThresholdBytes(0));
        assertThrows(IllegalArgumentException.class, () -> options().withCompactionTrigger(1));
        assertThrows(IllegalArgumentException.class, () -> options().withDurability(null));
    }

    @Test
    void compactionCoexistsWithUnflushedMemtable(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("old"));
            db.flush();
            db.put(b("inny"), b("x"));
            db.flush();

            db.put(b("k"), b("fresh")); // stays in the memtable, outside the merge
            db.compact();

            assertEquals(1, db.sstableCount());
            assertArrayEquals(b("fresh"), db.get(b("k")).orElseThrow(),
                    "the memtable still beats the merged table");
            assertArrayEquals(b("x"), db.get(b("inny")).orElseThrow());
        }
    }
}
