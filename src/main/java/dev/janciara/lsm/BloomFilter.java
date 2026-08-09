package dev.janciara.lsm;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Filtr Blooma — probabilistyczny zbior odpowiadajacy na jedno pytanie: „czy tego klucza na pewno
 * tu nie ma?".
 *
 * <p>Odpowiedz {@code false} z {@link #mightContain} jest <b>pewna</b>: klucza nie ma.
 * Odpowiedz {@code true} moze byc falszywym alarmem i wymaga sprawdzenia na dysku. Falszywych
 * negatywow nie ma z konstrukcji — dodanie klucza zapala bity, ktore nigdy nie gasna.
 *
 * <p>Po co to w LSM: chybiony odczyt musi odwiedzic <b>kazda</b> tabele, bo brak klucza w jednej
 * nic nie mowi o pozostalych. Filtr trzymany w pamieci zbija te wizyty do zera dla wiekszosci
 * tabel, zamieniajac odczyt z dysku na kilka operacji na bitach.
 *
 * <p>Rozmiar: {@value #DEFAULT_BITS_PER_KEY} bitow na klucz i {@code k = bity * ln2} funkcji
 * mieszajacych to okolice minimum bledu dla tego rozmiaru — ok. 1% falszywych trafien przy ~1,25 B
 * na klucz. Zamiast liczyc {@code k} niezaleznych hashy uzywamy podwojnego haszowania
 * (Kirsch-Mitzenmacher): {@code h_i = h1 + i*h2}. Daje to praktycznie ten sam rozklad przy koszcie
 * jednego hasha, i tak samo robia to prawdziwe silniki.
 */
public final class BloomFilter {

    /** Kompromis rozmiar/blad: ~1% falszywych trafien. */
    static final int DEFAULT_BITS_PER_KEY = 10;

    private final long[] words;
    private final int bitCount;
    private final int hashCount;

    private BloomFilter(long[] words, int bitCount, int hashCount) {
        this.words = words;
        this.bitCount = bitCount;
        this.hashCount = hashCount;
    }

    /**
     * Buduje filtr z gotowych hashy kluczy — {@link SSTable.Writer} liczy je w locie, bo w chwili
     * zapisu pierwszego rekordu nie wie jeszcze, ilu kluczy sie doczeka, a rozmiar filtra zalezy
     * wlasnie od tej liczby.
     *
     * @param count ile poczatkowych pozycji {@code keyHashes} jest realnie wypelnionych
     */
    public static BloomFilter build(long[] keyHashes, int count) {
        return build(keyHashes, count, DEFAULT_BITS_PER_KEY);
    }

    static BloomFilter build(long[] keyHashes, int count, int bitsPerKey) {
        if (count < 0 || count > keyHashes.length) throw new IllegalArgumentException("bledny count");
        if (bitsPerKey < 1) throw new IllegalArgumentException("bitsPerKey musi byc >= 1");

        int bitCount = Math.max(64, count * bitsPerKey);
        int hashCount = Math.max(1, Math.min(30, (int) Math.round(bitsPerKey * Math.log(2))));
        var filter = new BloomFilter(new long[(bitCount + 63) / 64], bitCount, hashCount);
        for (int i = 0; i < count; i++) {
            filter.addHash(keyHashes[i]);
        }
        return filter;
    }

    /** {@code false} = klucza na pewno nie ma. {@code true} = moze byc, trzeba sprawdzic. */
    public boolean mightContain(byte[] key) {
        return mightContainHash(hash(key));
    }

    boolean mightContainHash(long keyHash) {
        long probe = keyHash;
        long delta = step(keyHash);
        for (int i = 0; i < hashCount; i++) {
            if ((words[wordIndex(probe)] & bitMask(probe)) == 0) return false;
            probe += delta;
        }
        return true;
    }

    private void addHash(long keyHash) {
        long probe = keyHash;
        long delta = step(keyHash);
        for (int i = 0; i < hashCount; i++) {
            words[wordIndex(probe)] |= bitMask(probe);
            probe += delta;
        }
    }

    /** FNV-1a 64 — prosty, szybki i wystarczajaco dobrze miesza jak na filtr. */
    public static long hash(byte[] key) {
        long h = 0xcbf29ce484222325L;
        for (byte b : key) {
            h ^= (b & 0xFF);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /**
     * Drugi hash do podwojnego haszowania. Nieparzysty, zeby przy potedze dwojki jako liczbie
     * bitow kolejne probki nie wpadaly w krotki cykl.
     */
    private static long step(long keyHash) {
        long z = keyHash;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return (z ^ (z >>> 31)) | 1L;
    }

    private int bitIndex(long probe) {
        return (int) Long.remainderUnsigned(probe, bitCount); // probe traktujemy jako unsigned
    }

    private int wordIndex(long probe) {
        return bitIndex(probe) >>> 6;
    }

    private long bitMask(long probe) {
        return 1L << (bitIndex(probe) & 63);
    }

    // ---- serializacja --------------------------------------------------------

    void writeTo(OutputStream out) throws IOException {
        Encoding.writeUVarLong(out, bitCount);
        Encoding.writeUVarLong(out, hashCount);
        Encoding.writeUVarLong(out, words.length);
        ByteBuffer buf = ByteBuffer.allocate(words.length * Long.BYTES);
        for (long w : words) {
            buf.putLong(w);
        }
        out.write(buf.array());
    }

    static BloomFilter readFrom(InputStream in) throws IOException {
        int bitCount = Math.toIntExact(Encoding.readUVarLong(in));
        int hashCount = Math.toIntExact(Encoding.readUVarLong(in));
        int wordCount = Math.toIntExact(Encoding.readUVarLong(in));
        if (bitCount <= 0 || hashCount <= 0 || wordCount != (bitCount + 63) / 64) {
            throw new IOException("uszkodzony filtr Blooma: bity=" + bitCount
                    + ", hashe=" + hashCount + ", slowa=" + wordCount);
        }

        byte[] raw = in.readNBytes(wordCount * Long.BYTES);
        if (raw.length != wordCount * Long.BYTES) {
            throw new EOFException("uciety filtr Blooma");
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        long[] words = new long[wordCount];
        for (int i = 0; i < wordCount; i++) {
            words[i] = buf.getLong();
        }
        return new BloomFilter(words, bitCount, hashCount);
    }

    public int bitCount() {
        return bitCount;
    }

    public int hashCount() {
        return hashCount;
    }

    /** Rozmiar na dysku/w pamieci, bez naglowkow. */
    public int byteSize() {
        return words.length * Long.BYTES;
    }

    @Override
    public String toString() {
        return "BloomFilter[" + bitCount + " bitow, " + hashCount + " hashy, "
                + Arrays.stream(words).filter(w -> w != 0).count() + " niepustych slow]";
    }
}
