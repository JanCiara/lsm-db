package dev.janciara.lsm;

/**
 * A single entry travelling through the whole engine: WAL, memtable, SSTable.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code key}       — the key (raw bytes, non-null reference, may have length 0)</li>
 *   <li>{@code value}     — the value; an empty array for a tombstone</li>
 *   <li>{@code tombstone} — true = deletion marker (nothing can be removed from an immutable SSTable)</li>
 *   <li>{@code seqNo}     — monotonic sequence number; for the same key, the higher one wins</li>
 * </ul>
 *
 * <p>Caveat: the record holds {@code byte[]}, so the auto-generated {@code equals/hashCode} compare
 * array references rather than contents. Tests compare bytes with {@code assertArrayEquals}. Deep
 * equality can be added if it ever becomes necessary.
 */
public record Record(byte[] key, byte[] value, boolean tombstone, long seqNo) {

    private static final byte[] EMPTY = new byte[0];

    public Record {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        if (value == null) throw new IllegalArgumentException("value must not be null");
        if (seqNo < 0) throw new IllegalArgumentException("seqNo must be >= 0");
    }

    /** An ordinary put entry. */
    public static Record value(byte[] key, byte[] value, long seqNo) {
        return new Record(key, value, false, seqNo);
    }

    /** A deletion entry (tombstone) — empty value. */
    public static Record tombstone(byte[] key, long seqNo) {
        return new Record(key, EMPTY, true, seqNo);
    }
}
