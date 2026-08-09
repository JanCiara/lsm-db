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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SSTableTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Zapis przez memtable, zeby wejscie bylo posortowane tak jak w prawdziwym zrzucie. */
    private static SSTable writeTable(Path file, Record... records) throws IOException {
        var mt = new MemTable();
        for (Record r : records) mt.put(r);
        return SSTable.write(file, mt.snapshot());
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

        assertTrue(t.get(b("c")).isEmpty(), "dziura w srodku zakresu");
        assertTrue(t.get(b("a")).isEmpty(), "przed minKey");
        assertTrue(t.get(b("z")).isEmpty(), "za maxKey");
        assertFalse(t.mightContain(b("z")));
        assertTrue(t.mightContain(b("c")), "zakres nie wyklucza dziur — to tylko zgrubny odsiew");
    }

    @Test
    void tombstoneSurvivesRoundTrip(@TempDir Path dir) throws IOException {
        SSTable t = writeTable(dir.resolve("t.sst"), Record.tombstone(b("k"), 7));

        Record r = t.get(b("k")).orElseThrow();
        assertTrue(r.tombstone(), "SSTable oddaje rekord jak lezy — tlumaczenie to rola LsmStore");
        assertEquals(7, r.seqNo());
    }

    @Test
    void metadataFromFooterIsReadBackOnOpen(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("t.sst");
        writeTable(file,
                Record.value(b("a"), b("1"), 3),
                Record.value(b("m"), b("2"), 9),
                Record.value(b("z"), b("3"), 5));

        SSTable reopened = SSTable.open(file);
        assertEquals(3, reopened.entryCount());
        assertEquals(9, reopened.maxSeqNo(), "stopka pamieta najwyzszy seqNo, nie ostatni zapisany");
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

        assertArrayEquals(lo, t.minKey(), "0x7F < 0x80 w porzadku unsigned");
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
        assertEquals(List.of("t.sst"), names, "po atomowej podmianie zostaje tylko plik docelowy");
    }

    @Test
    void truncatedFileIsRejectedOnOpen(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("t.sst");
        writeTable(file, Record.value(b("k"), b("v"), 0));

        byte[] full = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(full, full.length - 3)); // urwany ogon = brak magic

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
            assertArrayEquals(b("a"), cursor.peek().key(), "peek nie przesuwa kursora");
            cursor.advance();
            assertArrayEquals(b("b"), cursor.peek().key());
            cursor.advance();
            assertNull(cursor.peek(), "null oznacza koniec tabeli");
        }
    }

    @Test
    void abandonedWriterLeavesNoTempFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("t.sst");
        try (SSTable.Writer writer = SSTable.writer(file)) {
            writer.add(Record.value(b("a"), b("1"), 0));
            // brak finish() — symuluje wyjatek w polowie scalania
        }

        assertFalse(Files.exists(file), "docelowy plik nie powstaje");
        assertFalse(Files.exists(dir.resolve("t.sst.tmp")), "smiec po sobie posprzatany");
    }

    // ---- M3: scalanie ------------------------------------------------------

    @Test
    void compactKeepsNewestVersionOfEachKey(@TempDir Path dir) throws IOException {
        SSTable older = writeTable(dir.resolve("0.sst"),
                Record.value(b("a"), b("stare-a"), 0),
                Record.value(b("b"), b("stare-b"), 1));
        SSTable newer = writeTable(dir.resolve("1.sst"),
                Record.value(b("b"), b("nowe-b"), 2),
                Record.value(b("c"), b("nowe-c"), 3));

        SSTable merged = SSTable.compact(dir.resolve("2.sst"), List.of(older, newer), true).orElseThrow();

        List<Record> all = merged.readAll();
        assertEquals(3, all.size(), "wspolny klucz zostaje raz");
        assertArrayEquals(b("stare-a"), all.get(0).value());
        assertArrayEquals(b("nowe-b"), all.get(1).value(), "wygrywa wersja z nowszej tabeli");
        assertArrayEquals(b("nowe-c"), all.get(2).value());
        assertEquals(3, merged.maxSeqNo());
    }

    @Test
    void compactInterleavesKeysFromManyTables(@TempDir Path dir) throws IOException {
        SSTable t0 = writeTable(dir.resolve("0.sst"), Record.value(b("a"), b("1"), 0),
                Record.value(b("d"), b("4"), 1));
        SSTable t1 = writeTable(dir.resolve("1.sst"), Record.value(b("b"), b("2"), 2));
        SSTable t2 = writeTable(dir.resolve("2.sst"), Record.value(b("c"), b("3"), 3),
                Record.value(b("e"), b("5"), 4));

        SSTable merged = SSTable.compact(dir.resolve("3.sst"), List.of(t0, t1, t2), true).orElseThrow();

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
                Record.value(b("zyje"), b("v"), 0),
                Record.value(b("znika"), b("v"), 1));
        SSTable newer = writeTable(dir.resolve("1.sst"), Record.tombstone(b("znika"), 2));

        SSTable merged = SSTable.compact(dir.resolve("2.sst"), List.of(older, newer), true).orElseThrow();

        List<Record> all = merged.readAll();
        assertEquals(1, all.size(), "i wartosc, i przykrywajacy ja tombstone znikaja");
        assertArrayEquals(b("zyje"), all.get(0).key());
    }

    @Test
    void compactKeepsTombstonesWhenOlderTablesRemain(@TempDir Path dir) throws IOException {
        SSTable older = writeTable(dir.resolve("0.sst"), Record.value(b("k"), b("v"), 0));
        SSTable newer = writeTable(dir.resolve("1.sst"), Record.tombstone(b("k"), 1));

        // dropTombstones=false: pod spodem moze lezec starsza tabela, ktorej tombstone wciaz pilnuje
        SSTable merged = SSTable.compact(dir.resolve("2.sst"), List.of(older, newer), false).orElseThrow();

        List<Record> all = merged.readAll();
        assertEquals(1, all.size());
        assertTrue(all.get(0).tombstone(), "tombstone przepisany, nie wyrzucony");
    }

    @Test
    void compactProducesNoFileWhenEverythingWasDeleted(@TempDir Path dir) throws IOException {
        SSTable older = writeTable(dir.resolve("0.sst"), Record.value(b("k"), b("v"), 0));
        SSTable newer = writeTable(dir.resolve("1.sst"), Record.tombstone(b("k"), 1));

        Path target = dir.resolve("2.sst");
        assertTrue(SSTable.compact(target, List.of(older, newer), true).isEmpty());
        assertFalse(Files.exists(target), "pusta tabela nie zostawia pliku");
        assertFalse(Files.exists(dir.resolve("2.sst.tmp")));
    }

    @Test
    void compactedTableCanDeleteItsSources(@TempDir Path dir) throws IOException {
        SSTable older = writeTable(dir.resolve("0.sst"), Record.value(b("a"), b("1"), 0));
        SSTable newer = writeTable(dir.resolve("1.sst"), Record.value(b("b"), b("2"), 1));

        SSTable merged = SSTable.compact(dir.resolve("2.sst"), List.of(older, newer), true).orElseThrow();
        // Zaden uchwyt do pliku nie przetrwal scalania — inaczej Windows odmowilby kasowania.
        older.delete();
        newer.delete();

        assertFalse(Files.exists(dir.resolve("0.sst")));
        assertArrayEquals(b("2"), merged.get(b("b")).orElseThrow().value());
    }
}
