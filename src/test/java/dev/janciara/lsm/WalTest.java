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
