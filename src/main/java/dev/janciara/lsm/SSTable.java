package dev.janciara.lsm;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Sorted String Table — <b>niemutowalny</b> plik z rekordami posortowanymi rosnaco po kluczu.
 * Powstaje przez zrzut ({@code flush}) memtable albo przez scalenie kilku starszych tabel
 * ({@link #compact}) i od tego momentu jest tylko czytany.
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
 * <p><b>Zapis jest atomowy:</b> {@link Writer} tworzy plik {@code .tmp}, fsync-uje go i dopiero
 * wtedy podmienia nazwe ({@code ATOMIC_MOVE}). Czytelnik nigdy nie zobaczy polowicznej tabeli —
 * albo pliku nie ma, albo jest kompletny.
 *
 * <p><b>Zaden uchwyt do pliku nie zyje dluzej niz pojedyncza operacja</b> — {@link #get} i
 * {@link Cursor} otwieraja strumien i zamykaja go po sobie. Dzieki temu scalona tabela moze
 * skasowac swoje zrodla nawet na Windows, ktory nie pozwala usunac otwartego pliku.
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
        try (Writer writer = writer(file)) {
            for (Record r : sorted) {
                writer.add(r);
            }
            return writer.finish().orElseThrow();
        }
    }

    /** Otwiera writer do strumieniowego budowania tabeli — uzywany przy zrzucie i przy scalaniu. */
    public static Writer writer(Path file) throws IOException {
        return new Writer(file);
    }

    /**
     * Buduje tabele rekord po rekordzie, bez trzymania calosci w pamieci.
     *
     * <p>Cykl zycia: {@link #add} n razy, potem {@link #finish}. Writer zamkniety bez
     * {@code finish} (wyjatek w srodku scalania) sprzata po sobie plik {@code .tmp} — dlatego
     * uzywaj go w {@code try-with-resources}.
     */
    public static final class Writer implements Closeable {

        private final Path file;
        private final Path tmp;
        private final FileOutputStream fos;
        private final OutputStream out;

        private long entryCount;
        private long maxSeqNo;
        private byte[] minKey;
        private byte[] prevKey;
        private boolean closed;

        private Writer(Path file) throws IOException {
            this.file = file;
            this.tmp = file.resolveSibling(file.getFileName() + ".tmp");
            this.fos = new FileOutputStream(tmp.toFile());
            try {
                this.out = new BufferedOutputStream(fos);
                out.write(MAGIC);
                out.write(VERSION);
            } catch (IOException e) {
                fos.close();
                Files.deleteIfExists(tmp);
                throw e;
            }
        }

        /** Dopisuje rekord; klucze musza isc scisle rosnaco (unsigned). */
        public void add(Record r) throws IOException {
            if (closed) throw new IllegalStateException("writer juz zamkniety");
            if (prevKey != null && Arrays.compareUnsigned(prevKey, r.key()) >= 0) {
                throw new IllegalArgumentException(
                        "rekordy musza byc posortowane rosnaco i bez duplikatow kluczy");
            }
            if (minKey == null) minKey = r.key();
            prevKey = r.key();
            Encoding.writeRecord(out, r);
            entryCount++;
            if (r.seqNo() > maxSeqNo) maxSeqNo = r.seqNo();
        }

        /**
         * Domyka plik: stopka, fsync, atomowa podmiana nazwy.
         *
         * @return pusty Optional gdy nie dodano ani jednego rekordu — wtedy zaden plik nie powstaje.
         *         Tak konczy sie scalanie, w ktorym wszystko okazalo sie tombstone'ami.
         */
        public Optional<SSTable> finish() throws IOException {
            if (closed) throw new IllegalStateException("writer juz zamkniety");
            closed = true;

            if (entryCount == 0) {
                out.close();
                Files.deleteIfExists(tmp);
                return Optional.empty();
            }

            byte[] footer = buildFooter(entryCount, maxSeqNo, minKey, prevKey);
            out.write(footer);
            out.write(intBE(footer.length));
            out.write(MAGIC);

            out.flush();        // bufor -> OS
            fos.getFD().sync(); // OS -> dysk; dopiero teraz podmiana nazwy cos gwarantuje
            out.close();

            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            syncDir(file.getParent());
            return Optional.of(new SSTable(file, entryCount, maxSeqNo, minKey, prevKey));
        }

        /** Po {@link #finish} nic nie robi; w przeciwnym razie kasuje niedokonczony plik tymczasowy. */
        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            out.close();
            Files.deleteIfExists(tmp);
        }
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

    // ---- scalanie (M3) -------------------------------------------------------

    /**
     * Scala kilka tabel w jedna, zostawiajac dla kazdego klucza tylko najswiezsza wersje.
     *
     * <p>Klasyczny k-way merge: kolejka priorytetowa trzyma po jednym kursorze na tabele,
     * uporzadkowanym po biezacym kluczu rosnaco, a przy remisie — po swiezosci tabeli malejaco.
     * Dzieki temu pierwszy wyjety rekord danego klucza jest zwyciezca, a wszystkie kolejne z tym
     * samym kluczem to przestarzale wersje do wyrzucenia. Pamiec zajmuje tylko k rekordow, nie
     * caly zbior — dlatego scalanie idzie przez {@link Cursor} i {@link Writer}, a nie przez listy.
     *
     * @param inputs        tabele od <b>najstarszej do najnowszej</b> — ta kolejnosc rozstrzyga remisy
     * @param dropTombstones czy wyrzucac tombstone'y zamiast je przepisywac. Wolno to zrobic
     *        <b>wylacznie</b> gdy {@code inputs} obejmuje takze najstarsza tabele w sklepie: tombstone
     *        istnieje po to, zeby przykrywac starsza wartosc, wiec usuniety zbyt wczesnie wskrzesza
     *        skasowany klucz. Przy scalaniu wszystkiego pod spodem nie zostaje juz nic do przykrycia.
     * @return nowa tabela, albo pusty Optional gdy po scaleniu nie zostal ani jeden rekord
     */
    public static Optional<SSTable> compact(Path target, List<SSTable> inputs, boolean dropTombstones)
            throws IOException {
        var cursors = new ArrayList<Cursor>(inputs.size());
        try (Writer writer = writer(target)) {
            var queue = new PriorityQueue<Source>(Math.max(1, inputs.size()), Source.BY_KEY_THEN_RECENCY);
            for (int rank = 0; rank < inputs.size(); rank++) {
                Cursor cursor = inputs.get(rank).cursor();
                cursors.add(cursor);
                if (cursor.peek() != null) queue.add(new Source(cursor, rank));
            }

            while (!queue.isEmpty()) {
                Source winner = queue.poll();
                Record newest = winner.cursor.peek();
                step(winner, queue);

                // Ten sam klucz w starszych tabelach — przewijamy, nie zapisujemy.
                while (!queue.isEmpty()
                        && Arrays.compareUnsigned(queue.peek().cursor.peek().key(), newest.key()) == 0) {
                    step(queue.poll(), queue);
                }

                if (newest.tombstone() && dropTombstones) continue;
                writer.add(newest);
            }
            return writer.finish();
        } finally {
            for (Cursor c : cursors) {
                try {
                    c.close();
                } catch (IOException ignored) {
                    // strumienie tylko do odczytu — blad zamkniecia nie zmienia wyniku scalania
                }
            }
        }
    }

    /** Przesuwa kursor i wklada go z powrotem do kolejki, o ile cos jeszcze zostalo. */
    private static void step(Source source, PriorityQueue<Source> queue) throws IOException {
        source.cursor.advance();
        if (source.cursor.peek() != null) queue.add(source);
    }

    /** Kursor + jego pozycja w porzadku swiezosci ({@code rank} rosnie z wiekiem tabeli w dol). */
    private static final class Source {

        static final Comparator<Source> BY_KEY_THEN_RECENCY =
                Comparator.<Source, byte[]>comparing(s -> s.cursor.peek().key(), Arrays::compareUnsigned)
                        .thenComparing(s -> s.rank, Comparator.reverseOrder());

        final Cursor cursor;
        final int rank;

        Source(Cursor cursor, int rank) {
            this.cursor = cursor;
            this.rank = rank;
        }
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
        try (Cursor cursor = cursor()) {
            Record r;
            while ((r = cursor.peek()) != null) {
                int cmp = Arrays.compareUnsigned(r.key(), key);
                if (cmp == 0) return Optional.of(r);
                if (cmp > 0) return Optional.empty(); // klucze rosna — dalej juz go nie bedzie
                cursor.advance();
            }
        }
        return Optional.empty();
    }

    /** Szybki odsiew po zakresie kluczy ze stopki — bez dotykania danych. */
    public boolean mightContain(byte[] key) {
        return Arrays.compareUnsigned(key, minKey) >= 0 && Arrays.compareUnsigned(key, maxKey) <= 0;
    }

    /** Wszystkie rekordy w kolejnosci rosnacych kluczy — wygodne w testach, kosztowne w pamieci. */
    public List<Record> readAll() throws IOException {
        var all = new ArrayList<Record>(Math.toIntExact(entryCount));
        try (Cursor cursor = cursor()) {
            Record r;
            while ((r = cursor.peek()) != null) {
                all.add(r);
                cursor.advance();
            }
        }
        return all;
    }

    /** Kursor ustawiony na pierwszym rekordzie. Wywolujacy go zamyka. */
    public Cursor cursor() throws IOException {
        InputStream in = dataStream();
        try {
            Cursor cursor = new Cursor(in);
            cursor.advance();
            return cursor;
        } catch (IOException e) {
            in.close();
            throw e;
        }
    }

    /**
     * Jednokierunkowy przesuw po rekordach tabeli. Rozdzielenie {@link #peek} od {@link #advance}
     * jest po to, ze scalanie musi <i>porownac</i> biezace klucze wszystkich zrodel, zanim
     * zdecyduje, ktore z nich przesunac.
     */
    public final class Cursor implements Closeable {

        private final InputStream in;
        private long remaining = entryCount;
        private Record current;

        private Cursor(InputStream in) {
            this.in = in;
        }

        /** Biezacy rekord albo {@code null}, gdy tabela sie skonczyla. Nie dotyka dysku. */
        public Record peek() {
            return current;
        }

        public void advance() throws IOException {
            if (remaining == 0) {
                current = null;
                return;
            }
            current = Encoding.readRecord(in);
            if (current == null) {
                throw new EOFException("SSTable " + file + " obiecuje " + entryCount
                        + " rekordow, a skonczyla sie " + remaining + " przed koncem");
            }
            remaining--;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** Kasuje plik tabeli. Uwaga na kolejnosc — patrz {@code LsmStore#compact}. */
    public void delete() throws IOException {
        Files.delete(file);
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
     * fsync katalogu, zeby podmiana nazwy albo skasowanie pliku przetrwalo crash OS-a. Windows nie
     * pozwala otworzyc katalogu jako pliku — tam odpuszczamy (i tak polegamy na atomowosci rename).
     */
    static void syncDir(Path dir) {
        if (dir == null) return;
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException e) {
            // Windows: AccessDeniedException — brak API na fsync katalogu.
        }
    }
}
