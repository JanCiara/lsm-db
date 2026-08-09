package dev.janciara.lsm;

import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Mutowalna, posortowana warstwa w pamieci — pierwszy przystanek dla kazdego zapisu.
 *
 * <p>Trzyma tylko <b>najswiezszy</b> rekord dla danego klucza: kolejny {@code put} na ten sam
 * klucz nadpisuje poprzedni (zwykla wartosc albo tombstone). {@link LsmStore} gwarantuje, ze
 * rekordy przychodza z rosnacym {@code seqNo}, wiec nadpisanie zawsze zachowuje najnowszy stan.
 *
 * <p>Klucze sa uporzadkowane <b>unsigned leksykograficznie</b> ({@link Arrays#compareUnsigned}) —
 * to standard w LSM i wymog pod M2 (SSTable zapisuje klucze posortowane). Dzieki temu bajt
 * {@code 0x80} jest wiekszy od {@code 0x7F}, a nie mniejszy (jak przy porownaniu ze znakiem).
 *
 * <p>Warstwa jest „glupia": {@link #get} zwraca caly {@link Record} (moze byc tombstone!).
 * Tlumaczenie tombstone → brak wartosci nalezy do {@link LsmStore}, nie tutaj.
 *
 * <p>Nie jest bezpieczna watkowo — M1 zaklada uzycie jednowatkowe.
 */
public final class MemTable {

    /**
     * Zryczaltowany narzut na wpis (wezel TreeMap, naglowki obiektow, referencje). Nie chodzi
     * o dokladny rachunek pamieci, tylko o to, zeby prog zrzutu nie ignorowal miliona pustych
     * kluczy — sam rozmiar danych bylby wtedy zerowy.
     */
    private static final long ENTRY_OVERHEAD_BYTES = 64;

    private final TreeMap<byte[], Record> entries = new TreeMap<>(Arrays::compareUnsigned);
    private long sizeInBytes;

    /** Wstawia lub nadpisuje wpis dla {@code r.key()}. */
    public void put(Record r) {
        Record replaced = entries.put(r.key(), r);
        if (replaced != null) sizeInBytes -= weigh(replaced);
        sizeInBytes += weigh(r);
    }

    /** Zwraca przechowywany rekord (moze byc tombstone), albo empty gdy klucza nie ma. */
    public Optional<Record> get(byte[] key) {
        return Optional.ofNullable(entries.get(key));
    }

    /**
     * Rekordy w kolejnosci rosnacych kluczy — dokladnie w postaci, jakiej oczekuje
     * {@link SSTable#write}. To <b>widok</b> na zywa mape, nie kopia: przeczytaj go do konca
     * (czyli zapisz tabele) zanim wywolasz {@link #clear()}.
     */
    public Collection<Record> snapshot() {
        return entries.values();
    }

    /** Kasuje wszystko — wolane po udanym zrzucie do SSTable. */
    public void clear() {
        entries.clear();
        sizeInBytes = 0;
    }

    public int size() {
        return entries.size();
    }

    /** Przyblizone zuzycie pamieci; {@link LsmStore} porownuje je z progiem zrzutu. */
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
