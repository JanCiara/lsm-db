package dev.janciara.lsm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LsmStoreTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
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
}
