package dev.janciara.lsm;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * A Bloom filter — a probabilistic set answering exactly one question: "is this key definitely not
 * here?"
 *
 * <p>A {@code false} from {@link #mightContain} is <b>certain</b>: the key is absent. A
 * {@code true} may be a false alarm and needs checking on disk. There are no false negatives by
 * construction — adding a key sets bits that are never cleared.
 *
 * <p>Why an LSM wants this: a missed read has to visit <b>every</b> table, because the absence of a
 * key in one says nothing about the others. Keeping the filter in memory drops those visits to zero
 * for most tables, turning a disk read into a handful of bit operations.
 *
 * <p>Sizing: {@value #DEFAULT_BITS_PER_KEY} bits per key and {@code k = bits * ln2} hash functions
 * sit near the error minimum for that size — about 1% false positives at ~1.25 B per key. Instead
 * of computing {@code k} independent hashes we use double hashing (Kirsch-Mitzenmacher):
 * {@code h_i = h1 + i*h2}. That gives practically the same distribution at the cost of one hash,
 * and it is what real engines do too.
 */
public final class BloomFilter {

    /** Size/error trade-off: ~1% false positives. */
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
     * Builds a filter from precomputed key hashes — {@link SSTable.Writer} accumulates them as it
     * goes, because when the first record is written it does not yet know how many keys will
     * follow, and the filter's size depends on exactly that number.
     *
     * @param count how many leading positions of {@code keyHashes} are actually populated
     */
    public static BloomFilter build(long[] keyHashes, int count) {
        return build(keyHashes, count, DEFAULT_BITS_PER_KEY);
    }

    static BloomFilter build(long[] keyHashes, int count, int bitsPerKey) {
        if (count < 0 || count > keyHashes.length) throw new IllegalArgumentException("bad count");
        if (bitsPerKey < 1) throw new IllegalArgumentException("bitsPerKey must be >= 1");

        int bitCount = Math.max(64, count * bitsPerKey);
        int hashCount = Math.max(1, Math.min(30, (int) Math.round(bitsPerKey * Math.log(2))));
        var filter = new BloomFilter(new long[(bitCount + 63) / 64], bitCount, hashCount);
        for (int i = 0; i < count; i++) {
            filter.addHash(keyHashes[i]);
        }
        return filter;
    }

    /** {@code false} = the key is definitely absent. {@code true} = maybe present, go and check. */
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

    /** FNV-1a 64 — simple, fast, and mixes well enough for a filter. */
    public static long hash(byte[] key) {
        long h = 0xcbf29ce484222325L;
        for (byte b : key) {
            h ^= (b & 0xFF);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /**
     * The second hash for double hashing. Forced odd so that, when the bit count is a power of two,
     * successive probes do not fall into a short cycle.
     */
    private static long step(long keyHash) {
        long z = keyHash;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return (z ^ (z >>> 31)) | 1L;
    }

    private int bitIndex(long probe) {
        return (int) Long.remainderUnsigned(probe, bitCount); // probe is treated as unsigned
    }

    private int wordIndex(long probe) {
        return bitIndex(probe) >>> 6;
    }

    private long bitMask(long probe) {
        return 1L << (bitIndex(probe) & 63);
    }

    // ---- serialisation -------------------------------------------------------

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
            throw new IOException("corrupt Bloom filter: bits=" + bitCount
                    + ", hashes=" + hashCount + ", words=" + wordCount);
        }

        byte[] raw = in.readNBytes(wordCount * Long.BYTES);
        if (raw.length != wordCount * Long.BYTES) {
            throw new EOFException("truncated Bloom filter");
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

    /** Size on disk / in memory, excluding headers. */
    public int byteSize() {
        return words.length * Long.BYTES;
    }

    @Override
    public String toString() {
        return "BloomFilter[" + bitCount + " bits, " + hashCount + " hashes, "
                + Arrays.stream(words).filter(w -> w != 0).count() + " non-empty words]";
    }
}
