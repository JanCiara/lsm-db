package dev.janciara.lsm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Pierwsza konkretna implementacja {@link KVStore} (M1): WAL + memtable.
 *
 * <p>Sciezka zapisu: kazdy {@code put}/{@code delete} najpierw dopisuje rekord do {@link Wal}
 * (trwalosc), a dopiero potem uwidacznia go w {@link MemTable} (widocznosc). To kolejnosc
 * „write-ahead" — gdy proces padnie po zapisie do WAL a przed czymkolwiek innym, {@link #open}
 * i tak odtworzy ten zapis.
 *
 * <p>Przy otwarciu odtwarzamy log do memtable i ustawiamy licznik {@code seqNo} na
 * {@code max(seqNo w logu) + 1}, zeby nowe zapisy mialy wyzszy numer niz cokolwiek historycznego.
 *
 * <p>W M1 wszystkie dane mieszcza sie w pamieci (brak zrzutu do SSTable — to M2), a WAL rosnie
 * bez ograniczen. Klasa nie jest bezpieczna watkowo — zaklada uzycie jednowatkowe.
 */
public final class LsmStore implements KVStore {

    private static final String WAL_FILE = "wal.log";

    private final MemTable memtable;
    private final Wal wal;
    private long nextSeqNo;

    private LsmStore(MemTable memtable, Wal wal, long nextSeqNo) {
        this.memtable = memtable;
        this.wal = wal;
        this.nextSeqNo = nextSeqNo;
    }

    /** Otwiera (lub tworzy) sklep w katalogu {@code dir}, odtwarzajac stan z WAL. */
    public static LsmStore open(Path dir) {
        try {
            Files.createDirectories(dir);
            Path walPath = dir.resolve(WAL_FILE);

            MemTable memtable = new MemTable();
            long[] maxSeq = {-1L}; // -1 => pusty log => nextSeqNo startuje od 0
            Wal.replay(walPath, r -> {
                memtable.put(r);
                if (r.seqNo() > maxSeq[0]) maxSeq[0] = r.seqNo();
            });

            Wal wal = Wal.open(walPath);
            return new LsmStore(memtable, wal, maxSeq[0] + 1);
        } catch (IOException e) {
            throw new UncheckedIOException("nie udalo sie otworzyc sklepu w " + dir, e);
        }
    }

    @Override
    public void put(byte[] key, byte[] value) {
        Record r = Record.value(key.clone(), value.clone(), nextSeqNo++);
        writeAhead(r);
    }

    @Override
    public void delete(byte[] key) {
        Record r = Record.tombstone(key.clone(), nextSeqNo++);
        writeAhead(r);
    }

    @Override
    public Optional<byte[]> get(byte[] key) {
        Optional<Record> found = memtable.get(key);
        if (found.isEmpty()) return Optional.empty();
        Record r = found.get();
        if (r.tombstone()) return Optional.empty();
        return Optional.of(r.value().clone());
    }

    @Override
    public void close() {
        try {
            wal.close();
        } catch (IOException e) {
            throw new UncheckedIOException("nie udalo sie zamknac WAL", e);
        }
    }

    /** WAL najpierw (trwalosc), memtable potem (widocznosc). */
    private void writeAhead(Record r) {
        try {
            wal.append(r);
        } catch (IOException e) {
            throw new UncheckedIOException("zapis do WAL nie powiodl sie", e);
        }
        memtable.put(r);
    }
}
