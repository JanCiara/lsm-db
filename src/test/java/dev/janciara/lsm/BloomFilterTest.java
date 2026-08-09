package dev.janciara.lsm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class BloomFilterTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static BloomFilter filterOf(String... keys) {
        long[] hashes = new long[Math.max(1, keys.length)];
        for (int i = 0; i < keys.length; i++) {
            hashes[i] = BloomFilter.hash(b(keys[i]));
        }
        return BloomFilter.build(hashes, keys.length);
    }

    @Test
    void everyAddedKeyIsReported() {
        BloomFilter f = filterOf("a", "b", "kot", "user:1", "");

        assertTrue(f.mightContain(b("a")));
        assertTrue(f.mightContain(b("b")));
        assertTrue(f.mightContain(b("kot")));
        assertTrue(f.mightContain(b("user:1")));
        assertTrue(f.mightContain(b("")), "pusty klucz tez jest kluczem");
    }

    @Test
    void missingKeysAreUsuallyRejected() {
        var keys = new String[1000];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = "klucz:" + i;
        }
        BloomFilter f = filterOf(keys);

        int falsePositives = 0;
        for (int i = 0; i < 10_000; i++) {
            if (f.mightContain(b("nieistniejacy:" + i))) falsePositives++;
        }
        // Teoria dla 10 bitow/klucz to ~1%; luzny prog, zeby test nie migotal.
        assertTrue(falsePositives < 500,
                "falszywych trafien " + falsePositives + "/10000, oczekiwane ok. 1%");
    }

    @Test
    void noFalseNegativesEvenWhenOverfilled() {
        var keys = new String[500];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = "k" + i;
        }
        // Ciasny filtr: 2 bity na klucz zamiast 10. Bledy w gore sa dozwolone, w dol nigdy.
        long[] hashes = new long[keys.length];
        for (int i = 0; i < keys.length; i++) {
            hashes[i] = BloomFilter.hash(b(keys[i]));
        }
        BloomFilter f = BloomFilter.build(hashes, keys.length, 2);

        for (String key : keys) {
            assertTrue(f.mightContain(b(key)), "falszywy negatyw dla " + key + " — to zawsze blad");
        }
    }

    @Test
    void sizeFollowsKeyCount() {
        var manyKeys = new String[2000];
        for (int i = 0; i < manyKeys.length; i++) {
            manyKeys[i] = "k" + i;
        }
        BloomFilter small = filterOf("a");
        BloomFilter big = filterOf(manyKeys);

        assertEquals(7, small.hashCount(), "k = round(10 * ln2)");
        assertEquals(20_000, big.bitCount(), "10 bitow na klucz");
        assertTrue(big.bitCount() > small.bitCount());
    }

    @Test
    void serializationRoundTrips() throws IOException {
        BloomFilter original = filterOf("a", "b", "c", "dlugi-klucz-z-myslnikami");

        var bos = new ByteArrayOutputStream();
        original.writeTo(bos);
        BloomFilter restored = BloomFilter.readFrom(new ByteArrayInputStream(bos.toByteArray()));

        assertEquals(original.bitCount(), restored.bitCount());
        assertEquals(original.hashCount(), restored.hashCount());
        assertEquals(original.byteSize(), restored.byteSize());
        assertTrue(restored.mightContain(b("a")));
        assertTrue(restored.mightContain(b("dlugi-klucz-z-myslnikami")));
        assertFalse(restored.mightContain(b("czegos-takiego-nie-bylo")));
    }

    @Test
    void corruptHeaderIsRejected() {
        byte[] bogus = {(byte) 0x00, (byte) 0x05, (byte) 0x01}; // bitCount = 0
        assertThrows(IOException.class,
                () -> BloomFilter.readFrom(new ByteArrayInputStream(bogus)));
    }

    @Test
    void emptyFilterRejectsEverything() {
        BloomFilter f = BloomFilter.build(new long[0], 0);

        assertFalse(f.mightContain(b("cokolwiek")));
        assertEquals(64, f.bitCount(), "minimalny rozmiar, zeby nie dzielic przez zero");
    }
}
