package dev.janciara.lsm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static long walSize(Path dir) throws IOException {
        return Files.size(dir.resolve("wal.log"));
    }

    /** Pliki tabel realnie lezace na dysku — do sprawdzania, czy scalanie po sobie sprzata. */
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
            value[1] = (byte) '9';           // caller mutuje po zapisie
            assertArrayEquals(b("v0"), db.get(b("k")).orElseThrow(), "sklep robi kopie obronna");
        }
    }

    @Test
    void valuesPersistAcrossReopen(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("a"), b("1"));
            db.put(b("b"), b("2"));
        }
        // Nowy proces/otwarcie — stan odtworzony wylacznie z WAL.
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
            assertTrue(db.get(b("k")).isEmpty(), "tombstone przetrwal replay");
        }
    }

    @Test
    void overwriteAfterReopenWins(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("old"));
        }
        // Po replay seqNo rosnie dalej, wiec nowy zapis nadpisuje stary.
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("new"));
            assertArrayEquals(b("new"), db.get(b("k")).orElseThrow());
        }
        try (LsmStore db = LsmStore.open(dir)) {
            assertArrayEquals(b("new"), db.get(b("k")).orElseThrow());
            assertFalse(db.get(b("k")).isEmpty());
        }
    }

    // ---- M2: zrzut do SSTable ----------------------------------------------

    @Test
    void flushMovesDataFromMemtableToSSTable(@TempDir Path dir) throws IOException {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("a"), b("1"));
            assertTrue(walSize(dir) > 0);

            db.flush();
            assertEquals(1, db.sstableCount());
            assertEquals(0, walSize(dir), "po zrzucie log jest niepotrzebny i zerowany");
            assertArrayEquals(b("1"), db.get(b("a")).orElseThrow(), "odczyt schodzi do SSTable");
        }
    }

    @Test
    void flushWithEmptyMemtableIsNoOp(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.flush();
            db.flush();
            assertEquals(0, db.sstableCount(), "nie tworzymy pustych plikow");
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
            assertEquals(1, db.sstableCount(), "tabela odnaleziona po nazwie pliku");
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
            assertArrayEquals(b("new"), db.get(b("k")).orElseThrow(), "memtable bije SSTable");

            db.flush();
            assertEquals(2, db.sstableCount());
            assertArrayEquals(b("new"), db.get(b("k")).orElseThrow(), "nowsza tabela bije starsza");
        }
    }

    @Test
    void tombstoneHidesValueLivingInOlderSSTable(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("v"));
            db.flush();

            db.delete(b("k"));
            assertTrue(db.get(b("k")).isEmpty(), "tombstone w memtable przykrywa SSTable");
            db.flush();
        }
        // Tombstone przetrwal jako zwykly rekord w nowszej tabeli — wartosc dalej niewidoczna.
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
            assertArrayEquals(b("1"), db.get(b("a")).orElseThrow(), "najstarsza tabela");
            assertArrayEquals(b("2"), db.get(b("b")).orElseThrow(), "nowsza tabela");
            assertArrayEquals(b("3"), db.get(b("c")).orElseThrow(), "jeszcze w memtable");
            assertTrue(db.get(b("nope")).isEmpty(), "chybienie przeglada wszystko i konczy pusto");
        }
    }

    @Test
    void memtableIsFlushedAutomaticallyAfterThreshold(@TempDir Path dir) throws IOException {
        // Prog dobrany tak, zeby zmiescilo sie kilka wpisow, a nie kilka tysiecy.
        try (LsmStore db = LsmStore.open(dir, 200)) {
            db.put(b("k0"), b("v0"));
            assertEquals(0, db.sstableCount());

            for (int i = 1; i < 10; i++) {
                db.put(b("k" + i), b("v" + i));
            }
            assertTrue(db.sstableCount() > 0, "prog wymusil zrzut bez recznego flush()");
            assertTrue(walSize(dir) < 200, "log zaczyna od zera po kazdym zrzucie");

            for (int i = 0; i < 10; i++) {
                assertArrayEquals(b("v" + i), db.get(b("k" + i)).orElseThrow(), "klucz k" + i);
            }
        }
    }

    @Test
    void seqNoContinuesAcrossFlushAndReopen(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("old"));
            db.flush(); // seqNo zyje juz tylko w stopce SSTable — WAL jest pusty
        }
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("new"));
            db.flush();
        }
        try (LsmStore db = LsmStore.open(dir)) {
            assertArrayEquals(b("new"), db.get(b("k")).orElseThrow(),
                    "licznik wznowiony ze stopki, wiec nowy zapis trafil do nowszej tabeli");
        }
    }

    @Test
    void walIsReplayedOnTopOfSSTables(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("flushed"));
            db.flush();
            db.put(b("k"), b("only-in-wal")); // brak flush — zamkniecie zostawia to w logu
        }
        try (LsmStore db = LsmStore.open(dir)) {
            assertArrayEquals(b("only-in-wal"), db.get(b("k")).orElseThrow(),
                    "replay wraca do memtable, ktora bije SSTable");
        }
    }

    @Test
    void leftoverTempFileIsRemovedOnOpen(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Path junk = dir.resolve("sst-000000.sst.tmp"); // niedokonczony zrzut sprzed crashu
        Files.writeString(junk, "polowa tabeli");

        try (LsmStore db = LsmStore.open(dir)) {
            assertEquals(0, db.sstableCount());
        }
        assertFalse(Files.exists(junk));
    }

    // ---- M3: scalanie tabel ------------------------------------------------

    @Test
    void compactionRunsAutomaticallyAfterTriggerCount(@TempDir Path dir) throws IOException {
        try (LsmStore db = LsmStore.open(dir, LsmStore.DEFAULT_FLUSH_THRESHOLD_BYTES, 3)) {
            db.put(b("a"), b("1"));
            db.flush();
            db.put(b("b"), b("2"));
            db.flush();
            assertEquals(2, db.sstableCount(), "jeszcze ponizej progu");

            db.put(b("c"), b("3"));
            db.flush();
            assertEquals(1, db.sstableCount(), "trzeci zrzut wyzwolil scalenie w jedna tabele");

            assertArrayEquals(b("1"), db.get(b("a")).orElseThrow());
            assertArrayEquals(b("2"), db.get(b("b")).orElseThrow());
            assertArrayEquals(b("3"), db.get(b("c")).orElseThrow());
        }
        assertEquals(1, sstFiles(dir).size(), "stare pliki skasowane z dysku");
    }

    @Test
    void compactionReclaimsSpaceAfterOverwrites(@TempDir Path dir) throws IOException {
        try (LsmStore db = LsmStore.open(dir)) {
            for (int i = 0; i < 5; i++) {
                db.put(b("k"), b("wersja-" + i)); // ten sam klucz, pieciokrotnie
                db.flush();
            }
            db.compact();
            assertArrayEquals(b("wersja-4"), db.get(b("k")).orElseThrow());
        }

        List<Path> files = sstFiles(dir);
        assertEquals(1, files.size());
        assertEquals(1, SSTable.open(files.get(0)).entryCount(),
                "piec wersji klucza sprowadzone do jednej — dopiero to odzyskuje miejsce");
    }

    @Test
    void compactionRemovesDeletedKeysFromDisk(@TempDir Path dir) throws IOException {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("zostaje"), b("v"));
            db.put(b("znika"), b("v"));
            db.flush();

            db.delete(b("znika"));
            db.flush();
            assertEquals(2, db.sstableCount());

            db.compact();
            assertTrue(db.get(b("znika")).isEmpty());
            assertArrayEquals(b("v"), db.get(b("zostaje")).orElseThrow());
        }

        List<Record> onDisk = SSTable.open(sstFiles(dir).get(0)).readAll();
        assertEquals(1, onDisk.size(), "ani wartosc, ani tombstone nie zajmuja juz miejsca");
        assertArrayEquals(b("zostaje"), onDisk.get(0).key());
    }

    @Test
    void compactingAnEntirelyDeletedStoreLeavesNoTables(@TempDir Path dir) throws IOException {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("v"));
            db.flush();
            db.delete(b("k"));
            db.flush();

            db.compact();
            assertEquals(0, db.sstableCount(), "nie zostalo nic do zapisania");
            assertTrue(db.get(b("k")).isEmpty());
        }
        assertTrue(sstFiles(dir).isEmpty());

        try (LsmStore db = LsmStore.open(dir)) {
            assertTrue(db.get(b("k")).isEmpty(), "klucz nie wraca po ponownym otwarciu");
        }
    }

    @Test
    void compactedDataSurvivesReopen(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("a"), b("stare"));
            db.flush();
            db.put(b("a"), b("nowe"));
            db.put(b("b"), b("2"));
            db.flush();
            db.compact();
        }
        try (LsmStore db = LsmStore.open(dir)) {
            assertEquals(1, db.sstableCount());
            assertArrayEquals(b("nowe"), db.get(b("a")).orElseThrow());
            assertArrayEquals(b("2"), db.get(b("b")).orElseThrow());
        }
    }

    @Test
    void writesAfterCompactionStillWin(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("stare"));
            db.flush();
            db.put(b("k"), b("srednie"));
            db.flush();
            db.compact();

            db.put(b("k"), b("nowe")); // seqNo musi byc wyzsze niz w scalonej tabeli
            db.flush();
            assertArrayEquals(b("nowe"), db.get(b("k")).orElseThrow());
        }
        try (LsmStore db = LsmStore.open(dir)) {
            assertArrayEquals(b("nowe"), db.get(b("k")).orElseThrow());
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
            assertEquals(1, db.sstableCount(), "jedna tabela nie ma sie z czym scalac");
        }
        assertEquals(1, sstFiles(dir).size(), "brak przepisanego pliku obok starego");
    }

    @Test
    void mixedWorkloadStaysCorrectAcrossFlushesAndCompaction(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir, LsmStore.DEFAULT_FLUSH_THRESHOLD_BYTES, 3)) {
            for (int i = 0; i < 30; i++) {
                db.put(b("k" + i), b("v" + i));
                if (i % 5 == 4) db.flush();
            }
            for (int i = 0; i < 30; i += 3) {
                db.put(b("k" + i), b("nadpisane" + i)); // co trzeci nadpisany
            }
            db.flush();
            for (int i = 0; i < 30; i += 7) {
                db.delete(b("k" + i)); // co siodmy skasowany
            }
            db.flush();
            db.compact();

            assertEquals(1, db.sstableCount());
            for (int i = 0; i < 30; i++) {
                if (i % 7 == 0) {
                    assertTrue(db.get(b("k" + i)).isEmpty(), "k" + i + " skasowany");
                } else if (i % 3 == 0) {
                    assertArrayEquals(b("nadpisane" + i), db.get(b("k" + i)).orElseThrow(), "k" + i);
                } else {
                    assertArrayEquals(b("v" + i), db.get(b("k" + i)).orElseThrow(), "k" + i);
                }
            }
        }
    }

    /** Stan po dwoch zrzutach: sst-000000 z wartoscia, sst-000001 z przykrywajacym ja tombstone'em. */
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
        // Scalenie daje pusty wynik, wiec zaden nowy plik nie powstaje. Symulujemy crash w polowie
        // sprzatania: compact() kasuje od najstarszej, wiec zdazyla zniknac tylko sst-000000.
        Files.delete(dir.resolve("sst-000000.sst"));

        try (LsmStore db = LsmStore.open(dir)) {
            assertTrue(db.get(b("k")).isEmpty(), "ocalaly tombstone dalej trzyma klucz skasowany");
        }
    }

    @Test
    void deletingNewerTableFirstWouldResurrectDeletedKey(@TempDir Path dir) throws IOException {
        valueThenTombstoneInSeparateTables(dir);
        // Ta sama sytuacja, ale sprzatanie poszlo od nowszej tabeli — czyli odwrotnie niz compact().
        Files.delete(dir.resolve("sst-000001.sst"));

        try (LsmStore db = LsmStore.open(dir)) {
            assertArrayEquals(b("v"), db.get(b("k")).orElseThrow(),
                    "bez tombstone'a wraca stara wartosc — dlatego compact() kasuje od najstarszej");
        }
    }

    @Test
    void compactionCoexistsWithUnflushedMemtable(@TempDir Path dir) {
        try (LsmStore db = LsmStore.open(dir)) {
            db.put(b("k"), b("stare"));
            db.flush();
            db.put(b("inny"), b("x"));
            db.flush();

            db.put(b("k"), b("swieze")); // zostaje w memtable, poza scalaniem
            db.compact();

            assertEquals(1, db.sstableCount());
            assertArrayEquals(b("swieze"), db.get(b("k")).orElseThrow(),
                    "memtable dalej bije scalona tabele");
            assertArrayEquals(b("x"), db.get(b("inny")).orElseThrow());
        }
    }
}
