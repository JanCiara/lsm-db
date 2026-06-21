package dev.janciara.lsm;

/**
 * Pojedynczy wpis przewijajacy sie przez caly silnik: WAL, memtable, SSTable.
 *
 * <p>Pola:
 * <ul>
 *   <li>{@code key}       — klucz (surowe bajty, niepusta referencja, moze byc dlugosci 0)</li>
 *   <li>{@code value}     — wartosc; dla tombstone pusta tablica</li>
 *   <li>{@code tombstone} — true = znacznik usuniecia (nie da sie usunac z niemutowalnej SSTable)</li>
 *   <li>{@code seqNo}     — monotoniczny numer sekwencyjny; przy tym samym kluczu wygrywa wyzszy</li>
 * </ul>
 *
 * <p>Uwaga (caveat M0): rekord trzyma {@code byte[]}, wiec auto-generowane {@code equals/hashCode}
 * porownuja referencje tablic, nie ich zawartosc. W testach porownujemy bajty przez
 * {@code assertArrayEquals}. Glebokie equals dorobimy jak bedzie potrzebne.
 */
public record Record(byte[] key, byte[] value, boolean tombstone, long seqNo) {

    private static final byte[] EMPTY = new byte[0];

    public Record {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        if (value == null) throw new IllegalArgumentException("value must not be null");
        if (seqNo < 0) throw new IllegalArgumentException("seqNo must be >= 0");
    }

    /** Zwykly wpis put. */
    public static Record value(byte[] key, byte[] value, long seqNo) {
        return new Record(key, value, false, seqNo);
    }

    /** Wpis usuwajacy (tombstone) — wartosc pusta. */
    public static Record tombstone(byte[] key, long seqNo) {
        return new Record(key, EMPTY, true, seqNo);
    }
}
