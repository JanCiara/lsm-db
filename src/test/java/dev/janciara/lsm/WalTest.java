package dev.janciara.lsm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<Record> replayAll(Path file) throws IOException {
        var out = new ArrayList<Record>();
        Wal.replay(file, out::add);
        return out;
    }

    @Test
    void appendThenReplayRoundTrips(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("wal.log");
        try (Wal wal = Wal.open(log)) {
            wal.append(Record.value(b("k0"), b("v0"), 0));
            wal.append(Record.value(b("k1"), b("v1"), 1));
            wal.append(Record.tombstone(b("k2"), 2));
        }

        List<Record> got = replayAll(log);
        assertEquals(3, got.size());
        assertArrayEquals(b("k0"), got.get(0).key());
        assertArrayEquals(b("v1"), got.get(1).value());
        assertTrue(got.get(2).tombstone());
        assertEquals(2, got.get(2).seqNo());
    }

    @Test
    void replayMissingFileIsNoOp(@TempDir Path dir) throws IOException {
        assertTrue(replayAll(dir.resolve("brak.log")).isEmpty());
    }

    @Test
    void replayEmptyFileIsNoOp(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("wal.log");
        Files.createFile(log);
        assertTrue(replayAll(log).isEmpty());
    }

    @Test
    void dataSurvivesCloseAndReopenForAppend(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("wal.log");
        try (Wal wal = Wal.open(log)) {
            wal.append(Record.value(b("a"), b("1"), 0));
        }
        // Ponowne otwarcie do dopisywania nie kasuje historii — nowe rekordy dochodza na koniec.
        try (Wal wal = Wal.open(log)) {
            wal.append(Record.value(b("b"), b("2"), 1));
        }

        List<Record> got = replayAll(log);
        assertEquals(2, got.size());
        assertArrayEquals(b("a"), got.get(0).key());
        assertArrayEquals(b("b"), got.get(1).key());
    }

    /** Ucina plik o {@code bytes} bajtow — tak wyglada crash w polowie zapisu rekordu. */
    private static void chopTail(Path file, int bytes) throws IOException {
        byte[] full = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(full, full.length - bytes));
    }

    @Test
    void truncatedLastRecordIsDroppedInsteadOfBreakingReplay(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("wal.log");
        try (Wal wal = Wal.open(log)) {
            wal.append(Record.value(b("a"), b("1"), 0));
            wal.append(Record.value(b("b"), b("2"), 1));
            wal.append(Record.value(b("c"), b("3"), 2));
        }
        long healthyBefore = Files.size(log);
        chopTail(log, 3); // ostatni rekord urwany w polowie

        List<Record> got = replayAll(log);
        assertEquals(2, got.size(), "kompletne rekordy odzyskane, niedokonczony pominiety");
        assertArrayEquals(b("a"), got.get(0).key());
        assertArrayEquals(b("b"), got.get(1).key());
        assertTrue(Files.size(log) < healthyBefore, "urwany ogon zostal odciety z pliku");
    }

    @Test
    void logStaysUsableAfterRecoveringFromTornTail(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("wal.log");
        try (Wal wal = Wal.open(log)) {
            wal.append(Record.value(b("a"), b("1"), 0));
            wal.append(Record.value(b("b"), b("2"), 1));
        }
        chopTail(log, 2);

        assertEquals(1, replayAll(log).size());
        // Po odcieciu ogona log musi dac sie dopisywac dalej — inaczej baza jest martwa na zawsze.
        try (Wal wal = Wal.open(log)) {
            wal.append(Record.value(b("c"), b("3"), 2));
        }

        List<Record> got = replayAll(log);
        assertEquals(2, got.size());
        assertArrayEquals(b("a"), got.get(0).key());
        assertArrayEquals(b("c"), got.get(1).key());
    }

    @Test
    void replayReportsHealthyLength(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("wal.log");
        try (Wal wal = Wal.open(log)) {
            wal.append(Record.value(b("a"), b("1"), 0));
        }
        long complete = Files.size(log);

        assertEquals(complete, Wal.replay(log, r -> { }), "caly plik zdrowy");

        chopTail(log, 1);
        assertEquals(0, Wal.replay(log, r -> { }), "jedyny rekord byl urwany — zdrowe zero bajtow");
    }

    @Test
    void bufferedDurabilityStillSurvivesProcessLevelClose(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("wal.log");
        try (Wal wal = Wal.open(log, Wal.Durability.OS_BUFFERED)) {
            wal.append(Record.value(b("k"), b("v"), 0));
        }

        List<Record> got = replayAll(log);
        assertEquals(1, got.size(), "bez fsync, ale dane sa juz w OS — pad procesu ich nie zabiera");
        assertArrayEquals(b("v"), got.get(0).value());
    }

    @Test
    void truncateClearsHistoryButKeepsLogUsable(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("wal.log");
        try (Wal wal = Wal.open(log)) {
            wal.append(Record.value(b("stary"), b("1"), 0));
            wal.truncate(); // tak jak po zrzucie memtable do SSTable
            assertEquals(0, Files.size(log));

            wal.append(Record.value(b("nowy"), b("2"), 1));
        }

        List<Record> got = replayAll(log);
        assertEquals(1, got.size(), "po truncate zostaja tylko rekordy dopisane pozniej");
        assertArrayEquals(b("nowy"), got.get(0).key());
    }
}
