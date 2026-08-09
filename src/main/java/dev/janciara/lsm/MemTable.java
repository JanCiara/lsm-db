package dev.janciara.lsm;

import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The mutable, sorted in-memory layer — first stop for every write.
 *
 * <p>Holds only the <b>freshest</b> record per key: another {@code put} on the same key overwrites
 * the previous one (a plain value or a tombstone). {@link LsmStore} guarantees records arrive with
 * increasing {@code seqNo}, so overwriting always keeps the newest state.
 *
 * <p>Keys are ordered <b>unsigned lexicographically</b> ({@link Arrays#compareUnsigned}) — the LSM
 * standard, and a hard requirement for SSTables, which store keys sorted. It makes byte
 * {@code 0x80} greater than {@code 0x7F} rather than smaller, as signed comparison would have it.
 *
 * <p>The layer is deliberately "dumb": {@link #get} returns the whole {@link Record}, tombstone
 * included. Translating a tombstone into "no value" is {@link LsmStore}'s job, not this class's.
 *
 * <p>Not thread-safe — the engine assumes single-threaded use.
 */
public final class MemTable {

    /**
     * Flat per-entry overhead (TreeMap node, object headers, references). This is not meant to be
     * an accurate memory accounting, only to stop the flush threshold from ignoring a million empty
     * keys — their data size alone would be zero.
     */
    private static final long ENTRY_OVERHEAD_BYTES = 64;

    private final TreeMap<byte[], Record> entries = new TreeMap<>(Arrays::compareUnsigned);
    private long sizeInBytes;

    /** Inserts or overwrites the entry for {@code r.key()}. */
    public void put(Record r) {
        Record replaced = entries.put(r.key(), r);
        if (replaced != null) sizeInBytes -= weigh(replaced);
        sizeInBytes += weigh(r);
    }

    /** Returns the stored record (possibly a tombstone), or empty when the key is absent. */
    public Optional<Record> get(byte[] key) {
        return Optional.ofNullable(entries.get(key));
    }

    /**
     * Records in increasing key order — exactly the shape {@link SSTable#write} expects. This is a
     * <b>view</b> onto the live map, not a copy: consume it fully (that is, write the table) before
     * calling {@link #clear()}.
     */
    public Collection<Record> snapshot() {
        return entries.values();
    }

    /** Drops everything — called after a successful flush to an SSTable. */
    public void clear() {
        entries.clear();
        sizeInBytes = 0;
    }

    public int size() {
        return entries.size();
    }

    /** Approximate memory footprint; {@link LsmStore} compares it against the flush threshold. */
    public long sizeInBytes() {
        return sizeInBytes;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    private static long weigh(Record r) {
        return r.key().length + r.value().length + ENTRY_OVERHEAD_BYTES;
    }
}
