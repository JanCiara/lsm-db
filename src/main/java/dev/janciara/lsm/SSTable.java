package dev.janciara.lsm;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Sorted String Table — <b>niemutowalny</b> plik z rekordami posortowanymi rosnaco po kluczu.
 * Powstaje przez zrzut ({@code flush}) memtable i od tego momentu jest tylko czytany.
 *
 * <p>Uklad pliku:
 * <pre>
 *   naglowek:  "LSMT" (4B) | wersja (1B)
 *   dane:      record[entryCount]            — format {@link Encoding}, klucze scisle rosnace
 *   stopka:    uvarint(entryCount) | uvarint(maxSeqNo) | blob(minKey) | blob(maxKey)
 *   zakonczenie: int32BE(dlugosc stopki) | "LSMT" (4B)
 * </pre>
 *
 * <p>Stopka jest na koncu, bo writer poznaje metadane dopiero po przejsciu calego strumienia
 * rekordow — nie chcemy buforowac wszystkiego w pamieci ani wracac na poczatek pliku. Stala
 * dlugosc zakonczenia (8B) pozwala ja znalezc: czytamy 8 ostatnich bajtow, z nich dlugosc stopki,
 * i dopiero potem sama stopke. Powtorzony magic na koncu jest tania kontrola, ze plik nie zostal
 * uciety w polowie zapisu.
 *
 * <p><b>Zapis jest atomowy:</b> {@link #write} tworzy plik {@code .tmp}, fsync-uje go i dopiero
 * wtedy podmienia nazwe ({@code ATOMIC_MOVE}). Czytelnik nigdy nie zobaczy polowicznej tabeli —
 * albo pliku nie ma, albo jest kompletny.
 *
 * <p><b>Wyszukiwanie w M2 jest liniowe</b> — skan od poczatku danych. Dwie tanie optymalizacje juz
 * dzialaja: {@code minKey}/{@code maxKey} ze stopki odsiewaja caly plik, a posortowanie kluczy
 * pozwala przerwac skan, gdy miniemy szukany klucz. Prawdziwy indeks blokowy i filtr Blooma to M4.
 */
public final class SSTable {

    /** Magic na poczatku i na samym koncu pliku. */
    static final byte[] MAGIC = {'L', 'S', 'M', 'T'};
    static final int VERSION = 1;

    private static final int HEADER_LEN = MAGIC.length + 1;                 // magic + wersja
    private static final int TAIL_LEN = Integer.BYTES + MAGIC.length;       // dlugosc stopki + magic

    private final Path file;
    private final long entryCount;
    private final long maxSeqNo;
    private final byte[] minKey;
    private final byte[] maxKey;

    private SSTable(Path file, long entryCount, long maxSeqNo, byte[] minKey, byte[] maxKey) {
        this.file = file;
        this.entryCount = entryCount;
        this.maxSeqNo = maxSeqNo;
        this.minKey = minKey;
        this.maxKey = maxKey;
    }

    // ---- zapis ---------------------------------------------------------------

    /**
     * Zapisuje rekordy do nowej tabeli pod sciezka {@code file} i zwraca gotowy uchwyt do odczytu.
     *
     * @param sorted rekordy posortowane rosnaco po kluczu (unsigned), bez powtorzen — dokladnie to,
     *               co oddaje {@link MemTable#snapshot()}. Naruszenie porzadku to blad programisty,
     *               wiec konczy sie {@link IllegalArgumentException}, a nie cicha korupcja pliku.
     */
    public static SSTable write(Path file, Collection<Record> sorted) throws IOException {
        if (sorted.isEmpty()) throw new IllegalArgumentException("pusta SSTable nie ma sensu");

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        long count = 0;
        long maxSeq = 0;
        byte[] minKey = null;
        byte[] prevKey = null;

        try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
            OutputStream out = new BufferedOutputStream(fos);
            out.write(MAGIC);
            out.write(VERSION);

            for (Record r : sorted) {
                if (prevKey != null && Arrays.compareUnsigned(prevKey, r.key()) >= 0) {
                    throw new IllegalArgumentException(
                            "rekordy musza byc posortowane rosnaco i bez duplikatow kluczy");
                }
                if (minKey == null) minKey = r.key();
                prevKey = r.key();
                Encoding.writeRecord(out, r);
                count++;
                if (r.seqNo() > maxSeq) maxSeq = r.seqNo();
            }

            byte[] footer = buildFooter(count, maxSeq, minKey, prevKey);
            out.write(footer);
            out.write(intBE(footer.length));
            out.write(MAGIC);

            out.flush();       // bufor -> OS
            fos.getFD().sync(); // OS -> dysk; dopiero teraz podmiana nazwy cos gwarantuje
        }

        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        syncDir(file.getParent());
        return new SSTable(file, count, maxSeq, minKey, prevKey);
    }

    private static byte[] buildFooter(long entryCount, long maxSeqNo, byte[] minKey, byte[] maxKey) {
        var bos = new ByteArrayOutputStream();
        try {
            Encoding.writeUVarLong(bos, entryCount);
            Encoding.writeUVarLong(bos, maxSeqNo);
            Encoding.writeBlob(bos, minKey);
            Encoding.writeBlob(bos, maxKey);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream nie rzuca realnie
        }
        return bos.toByteArray();
    }

    // ---- odczyt --------------------------------------------------------------

    /** Otwiera istniejaca tabele: waliduje naglowek i wczytuje metadane ze stopki. */
    public static SSTable open(Path file) throws IOException {
        long size = Files.size(file);
        if (size < HEADER_LEN + TAIL_LEN) {
            throw new IOException("plik za krotki jak na SSTable: " + file);
        }

        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            byte[] header = readAt(ch, 0, HEADER_LEN);
            if (!Arrays.equals(MAGIC, Arrays.copyOf(header, MAGIC.length))) {
                throw new IOException("to nie jest SSTable (zly magic): " + file);
            }
            int version = header[MAGIC.length] & 0xFF;
            if (version != VERSION) {
                throw new IOException("nieobslugiwana wersja SSTable " + version + " w " + file);
            }

            byte[] tail = readAt(ch, size - TAIL_LEN, TAIL_LEN);
            if (!Arrays.equals(MAGIC, Arrays.copyOfRange(tail, Integer.BYTES, TAIL_LEN))) {
                throw new IOException("brak magic na koncu — plik uciety lub uszkodzony: " + file);
            }
            int footerLen = ByteBuffer.wrap(tail, 0, Integer.BYTES).getInt();
            if (footerLen < 0 || HEADER_LEN + (long) footerLen + TAIL_LEN > size) {
                throw new IOException("bledna dlugosc stopki (" + footerLen + ") w " + file);
            }

            byte[] footer = readAt(ch, size - TAIL_LEN - footerLen, footerLen);
            var in = new ByteArrayInputStream(footer);
            long entryCount = Encoding.readUVarLong(in);
            long maxSeqNo = Encoding.readUVarLong(in);
            byte[] minKey = Encoding.readBlob(in);
            byte[] maxKey = Encoding.readBlob(in);
            return new SSTable(file, entryCount, maxSeqNo, minKey, maxKey);
        }
    }

    /**
     * Szuka rekordu o dokladnie tym kluczu. Zwraca caly {@link Record} — moze byc tombstone,
     * bo tabela jest niemutowalna i „usuniecie" zyje w niej jako zwykly wpis ze znacznikiem.
     */
    public Optional<Record> get(byte[] key) throws IOException {
        if (!mightContain(key)) return Optional.empty();
        try (InputStream in = dataStream()) {
            for (long i = 0; i < entryCount; i++) {
                Record r = readOrFail(in, i);
                int cmp = Arrays.compareUnsigned(r.key(), key);
                if (cmp == 0) return Optional.of(r);
                if (cmp > 0) return Optional.empty(); // klucze rosna — dalej juz go nie bedzie
            }
        }
        return Optional.empty();
    }

    /** Szybki odsiew po zakresie kluczy ze stopki — bez dotykania danych. */
    public boolean mightContain(byte[] key) {
        return Arrays.compareUnsigned(key, minKey) >= 0 && Arrays.compareUnsigned(key, maxKey) <= 0;
    }

    /** Wszystkie rekordy w kolejnosci rosnacych kluczy — pod compaction (M3) i testy. */
    public List<Record> readAll() throws IOException {
        var out = new ArrayList<Record>(Math.toIntExact(entryCount));
        try (InputStream in = dataStream()) {
            for (long i = 0; i < entryCount; i++) {
                out.add(readOrFail(in, i));
            }
        }
        return out;
    }

    public Path path() {
        return file;
    }

    public long entryCount() {
        return entryCount;
    }

    /** Najwyzszy {@code seqNo} w tabeli — {@link LsmStore} wznawia od niego licznik po restarcie. */
    public long maxSeqNo() {
        return maxSeqNo;
    }

    public byte[] minKey() {
        return minKey.clone();
    }

    public byte[] maxKey() {
        return maxKey.clone();
    }

    @Override
    public String toString() {
        return "SSTable[" + file.getFileName() + ", " + entryCount + " rekordow]";
    }

    // ---- wewnetrzne ----------------------------------------------------------

    /** Strumien ustawiony na pierwszym rekordzie (za naglowkiem). */
    private InputStream dataStream() throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(file));
        try {
            in.skipNBytes(HEADER_LEN);
        } catch (IOException e) {
            in.close();
            throw e;
        }
        return in;
    }

    /**
     * Czyta rekord nr {@code index}. Stopka mowi ile ich jest, wiec czytamy dokladnie tyle i nie
     * musimy odrozniac konca danych od poczatku stopki — a niezgodnosc licznika z trescia od razu
     * wychodzi jako blad zamiast smieciowego rekordu.
     */
    private Record readOrFail(InputStream in, long index) throws IOException {
        Record r = Encoding.readRecord(in);
        if (r == null) {
            throw new EOFException("SSTable " + file + " obiecuje " + entryCount
                    + " rekordow, a skonczyla sie na " + index);
        }
        return r;
    }

    private static byte[] readAt(FileChannel ch, long position, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        while (buf.hasRemaining()) {
            if (ch.read(buf, position + buf.position()) < 0) {
                throw new EOFException("nieoczekiwany koniec pliku przy offsecie " + position);
            }
        }
        return buf.array();
    }

    private static byte[] intBE(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    /**
     * fsync katalogu, zeby sama podmiana nazwy przetrwala crash OS-a. Windows nie pozwala otworzyc
     * katalogu jako pliku — tam po prostu odpuszczamy (i tak polegamy na tym, ze rename jest atomowy).
     */
    private static void syncDir(Path dir) {
        if (dir == null) return;
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException e) {
            // Windows: AccessDeniedException — brak API na fsync katalogu.
        }
    }
}
