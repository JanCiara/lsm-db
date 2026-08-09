package dev.janciara.lsm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Implementacja {@link KVStore} (M3): WAL + memtable + niemutowalne SSTable + scalanie tabel.
 *
 * <p><b>Sciezka zapisu.</b> Kazdy {@code put}/{@code delete} najpierw dopisuje rekord do
 * {@link Wal} (trwalosc), a dopiero potem uwidacznia go w {@link MemTable} (widocznosc). Gdy
 * memtable przekroczy prog rozmiaru, jej zawartosc laduje w nowej {@link SSTable}, memtable
 * startuje pusta, a WAL jest zerowany.
 *
 * <p><b>Sciezka odczytu</b> idzie od najswiezszej warstwy do najstarszej: memtable, potem SSTable
 * od najnowszej do najstarszej. Pierwsze trafienie wygrywa — nie trzeba porownywac {@code seqNo},
 * bo kazdy zrzut zawiera rekordy nowsze niz wszystko, co zrzucono wczesniej. Znaleziony rekord
 * moze byc tombstone; wtedy klucz jest dla swiata usuniety, mimo ze starsza tabela ma dla niego
 * wartosc. To wlasnie dlatego usuniecie moze byc zapisem.
 *
 * <p><b>Odtwarzanie po restarcie.</b> {@link #open} wczytuje metadane wszystkich plikow
 * {@code sst-*.sst}, odtwarza WAL do memtable i ustawia licznik {@code seqNo} na
 * {@code max(seqNo w SSTable i w logu) + 1}. Crash pomiedzy zapisem SSTable a wyczyszczeniem WAL
 * jest nieszkodliwy: replay wroci rekordami, ktore juz sa na dysku, a te trafia do memtable —
 * czyli do warstwy wygrywajacej. Zaden odczyt nie zobaczy przez to starszej wartosci.
 *
 * <p><b>Compaction.</b> Bez scalania liczba tabel roslaby bez konca, a nadpisany albo skasowany
 * klucz zajmowalby miejsce na zawsze — tombstone to przeciez tez zapis. Po przekroczeniu progu
 * liczby tabel {@link #compact()} scala wszystkie w jedna, zostawiajac po jednej, najswiezszej
 * wersji kazdego klucza i wyrzucajac tombstone'y.
 *
 * <p>Klasa nie jest bezpieczna watkowo — zaklada uzycie jednowatkowe.
 */
public final class LsmStore implements KVStore {

    private static final String WAL_FILE = "wal.log";
    private static final String SST_PREFIX = "sst-";
    private static final String SST_SUFFIX = ".sst";
    private static final String TMP_SUFFIX = ".tmp";

    /** Domyslny prog zrzutu memtable na dysk. */
    public static final long DEFAULT_FLUSH_THRESHOLD_BYTES = 4L * 1024 * 1024;
    /** Domyslna liczba tabel, po ktorej uruchamia sie scalanie. */
    public static final int DEFAULT_COMPACTION_TRIGGER = 4;

    private final Path dir;
    private final MemTable memtable;
    private final Wal wal;
    /** Od najstarszej do najnowszej — odczyt przeglada te liste od konca. */
    private final List<SSTable> sstables;
    private final long flushThresholdBytes;
    private final int compactionTrigger;
    private long nextSeqNo;
    private long nextTableNumber;

    private LsmStore(Path dir, MemTable memtable, Wal wal, List<SSTable> sstables,
                     long flushThresholdBytes, int compactionTrigger,
                     long nextSeqNo, long nextTableNumber) {
        this.dir = dir;
        this.memtable = memtable;
        this.wal = wal;
        this.sstables = sstables;
        this.flushThresholdBytes = flushThresholdBytes;
        this.compactionTrigger = compactionTrigger;
        this.nextSeqNo = nextSeqNo;
        this.nextTableNumber = nextTableNumber;
    }

    /** Otwiera (lub tworzy) sklep w katalogu {@code dir} z domyslnymi progami. */
    public static LsmStore open(Path dir) {
        return open(dir, DEFAULT_FLUSH_THRESHOLD_BYTES, DEFAULT_COMPACTION_TRIGGER);
    }

    /** Jak {@link #open(Path)}, ale z wlasnym progiem zrzutu memtable (w bajtach). */
    public static LsmStore open(Path dir, long flushThresholdBytes) {
        return open(dir, flushThresholdBytes, DEFAULT_COMPACTION_TRIGGER);
    }

    /**
     * Jak {@link #open(Path)}, ale z wlasnym progiem zrzutu memtable (w bajtach) i wlasna liczba
     * tabel wyzwalajaca scalanie. Male wartosci wymuszaja czeste zrzuty i scalania — przydatne
     * w testach i przy zabawie z ksztaltem drzewa.
     */
    public static LsmStore open(Path dir, long flushThresholdBytes, int compactionTrigger) {
        if (flushThresholdBytes <= 0) {
            throw new IllegalArgumentException("prog zrzutu musi byc dodatni");
        }
        if (compactionTrigger < 2) {
            throw new IllegalArgumentException("scalanie ma sens dopiero od 2 tabel");
        }
        try {
            Files.createDirectories(dir);
            deleteLeftoverTempFiles(dir);

            List<SSTable> sstables = openSSTables(dir);
            long maxSeqNo = -1L; // -1 => brak historii => nextSeqNo startuje od 0
            long maxTableNumber = -1L;
            for (SSTable t : sstables) {
                maxSeqNo = Math.max(maxSeqNo, t.maxSeqNo());
                maxTableNumber = Math.max(maxTableNumber, tableNumber(t.path()));
            }

            MemTable memtable = new MemTable();
            long[] seqNo = {maxSeqNo};
            Wal.replay(dir.resolve(WAL_FILE), r -> {
                memtable.put(r);
                if (r.seqNo() > seqNo[0]) seqNo[0] = r.seqNo();
            });

            Wal wal = Wal.open(dir.resolve(WAL_FILE));
            return new LsmStore(dir, memtable, wal, sstables, flushThresholdBytes,
                    compactionTrigger, seqNo[0] + 1, maxTableNumber + 1);
        } catch (IOException e) {
            throw new UncheckedIOException("nie udalo sie otworzyc sklepu w " + dir, e);
        }
    }

    @Override
    public void put(byte[] key, byte[] value) {
        writeAhead(Record.value(key.clone(), value.clone(), nextSeqNo++));
    }

    @Override
    public void delete(byte[] key) {
        writeAhead(Record.tombstone(key.clone(), nextSeqNo++));
    }

    @Override
    public Optional<byte[]> get(byte[] key) {
        Optional<Record> found = memtable.get(key);
        if (found.isEmpty()) found = searchSSTables(key);
        if (found.isEmpty()) return Optional.empty();

        Record r = found.get();
        if (r.tombstone()) return Optional.empty();
        return Optional.of(r.value().clone());
    }

    /**
     * Zrzuca memtable do nowej SSTable i zeruje WAL. Pusta memtable = no-op (nie robimy pustych
     * plikow). Normalnie wola sie samo po przekroczeniu progu; recznie przydaje sie w testach
     * i gdy chcemy zamknac sklep z „czystym" logiem.
     */
    public void flush() {
        if (memtable.isEmpty()) return;
        try {
            sstables.add(SSTable.write(nextTablePath(), memtable.snapshot()));
            memtable.clear();
            wal.truncate();
        } catch (IOException e) {
            throw new UncheckedIOException("zrzut memtable do SSTable nie powiodl sie", e);
        }
        if (sstables.size() >= compactionTrigger) compact();
    }

    /**
     * Scala wszystkie SSTable w jedna: kazdy klucz zostaje w najswiezszej wersji, a tombstone'y
     * znikaja calkiem. Dopiero to odzyskuje miejsce po nadpisaniach i usunieciach — do tego momentu
     * kazdy zapis, takze {@code delete}, tylko powieksza zbior plikow. No-op ponizej dwoch tabel.
     *
     * <p><b>Odpornosc na crash</b> opiera sie na dwoch rzeczach i warto rozumiec obie:
     *
     * <p>1. Scalona tabela dostaje <b>numer wyzszy</b> niz wszystkie wejsciowe, wiec od momentu
     * pojawienia sie na dysku przykrywa je w odczycie. Crash po jej zapisaniu, a przed skasowaniem
     * zrodel, zostawia tylko martwe pliki — nigdy zlych danych.
     *
     * <p>2. Zrodla kasujemy <b>od najstarszego</b>. To nie jest kosmetyka: gdyby posypac sie
     * odwrotnie, crash moglby zabrac tombstone'a, zostawiajac pod nim starsza wartosc tego samego
     * klucza — i skasowany klucz wracalby z martwych. Kasowanie od dolu gwarantuje, ze zbior
     * ocalalych tabel to zawsze <i>koncowy</i> fragment historii, w ktorym najswiezszy rekord
     * kazdego klucza wciaz jest obecny.
     */
    public void compact() {
        if (sstables.size() < 2) return;
        try {
            List<SSTable> inputs = List.copyOf(sstables);
            // Scalamy komplet tabel, wiec pod spodem nie zostaje nic, co tombstone mialby przykrywac.
            Optional<SSTable> merged = SSTable.compact(nextTablePath(), inputs, /*dropTombstones*/ true);

            for (SSTable stale : inputs) { // kolejnosc listy = od najstarszej
                stale.delete();
            }
            SSTable.syncDir(dir);

            sstables.clear();
            merged.ifPresent(sstables::add);
        } catch (IOException e) {
            throw new UncheckedIOException("scalanie SSTable nie powiodlo sie", e);
        }
    }

    /** Liczba SSTable na dysku — podglad stanu silnika (testy, przyszle metryki). */
    public int sstableCount() {
        return sstables.size();
    }

    @Override
    public void close() {
        try {
            wal.close();
        } catch (IOException e) {
            throw new UncheckedIOException("nie udalo sie zamknac WAL", e);
        }
    }

    // ---- wewnetrzne ----------------------------------------------------------

    /** WAL najpierw (trwalosc), memtable potem (widocznosc), na koncu ewentualny zrzut. */
    private void writeAhead(Record r) {
        try {
            wal.append(r);
        } catch (IOException e) {
            throw new UncheckedIOException("zapis do WAL nie powiodl sie", e);
        }
        memtable.put(r);
        if (memtable.sizeInBytes() >= flushThresholdBytes) flush();
    }

    /** Sciezka kolejnej tabeli; numer rosnie z kazdym zrzutem i scaleniem. */
    private Path nextTablePath() {
        return dir.resolve(String.format("%s%06d%s", SST_PREFIX, nextTableNumber++, SST_SUFFIX));
    }

    /** Przeglada tabele od najnowszej — pierwsze trafienie jest z definicji najswiezsze. */
    private Optional<Record> searchSSTables(byte[] key) {
        for (int i = sstables.size() - 1; i >= 0; i--) {
            SSTable table = sstables.get(i);
            try {
                Optional<Record> found = table.get(key);
                if (found.isPresent()) return found;
            } catch (IOException e) {
                throw new UncheckedIOException("nie udalo sie odczytac " + table.path(), e);
            }
        }
        return Optional.empty();
    }

    /** Wczytuje metadane tabel z katalogu, posortowane rosnaco po numerze (czyli od najstarszej). */
    private static List<SSTable> openSSTables(Path dir) throws IOException {
        var files = new ArrayList<Path>();
        try (DirectoryStream<Path> ls = Files.newDirectoryStream(dir, SST_PREFIX + "*" + SST_SUFFIX)) {
            ls.forEach(files::add);
        }
        files.sort((a, b) -> Long.compare(tableNumber(a), tableNumber(b)));

        var tables = new ArrayList<SSTable>(files.size());
        for (Path f : files) {
            tables.add(SSTable.open(f));
        }
        return tables;
    }

    /**
     * Numer z nazwy {@code sst-000007.sst}. Rosnie z kazdym zrzutem, wiec porzadek nazw = porzadek
     * swiezosci danych. Nazwa nie pasujaca do wzorca konczy otwarcie bledem, zamiast po cichu
     * ustawic tabele w zlej kolejnosci.
     */
    private static long tableNumber(Path file) {
        String name = file.getFileName().toString();
        String digits = name.substring(SST_PREFIX.length(), name.length() - SST_SUFFIX.length());
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("nieoczekiwana nazwa pliku SSTable: " + name, e);
        }
    }

    /** Niedokonczone zapisy z poprzedniego zycia procesu — bezpieczne do skasowania. */
    private static void deleteLeftoverTempFiles(Path dir) throws IOException {
        try (DirectoryStream<Path> ls = Files.newDirectoryStream(dir, "*" + TMP_SUFFIX)) {
            for (Path f : ls) {
                Files.deleteIfExists(f);
            }
        }
    }
}
