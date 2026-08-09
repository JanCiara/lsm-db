package dev.janciara.lsm;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/**
 * Write-ahead log — append-only plik, do ktorego kazda mutacja trafia <b>zanim</b> zostanie
 * uwidoczniona w {@link MemTable}. Dzieki temu po restarcie/crashu mozna odtworzyc stan pamieci
 * przez {@link #replay(Path, Consumer)}.
 *
 * <p><b>Trwalosc</b> jest sterowana przez {@link Durability}. Domyslny {@link Durability#SYNC}
 * robi po kazdym rekordzie {@code flush} bufora do OS oraz {@code fsync}
 * ({@link FileDescriptor#sync()}) na dysk — wolne, ale przezywa nawet zanik pradu, czyli caly sens
 * WAL. {@link Durability#OS_BUFFERED} pomija fsync: chroni przed padem procesu, ale nie maszyny.
 *
 * <p><b>Urwany ogon.</b> Crash w polowie {@link #append} zostawia niekompletny rekord na koncu
 * pliku. {@link #replay} traktuje to jako normalny scenariusz: oddaje wszystkie kompletne rekordy,
 * ucina log do ostatniej zdrowej granicy i zwraca jej offset. Zapis, ktory nie zdazyl sie domknac,
 * nigdy nie zostal potwierdzony wywolujacemu, wiec jego utrata nie lamie zadnej obietnicy —
 * natomiast wysypanie sie na starcie zamiast tego lamaloby cala idee logu.
 *
 * <p>Czego to nie wykrywa: uszkodzenia <i>w srodku</i> pliku, ktore przypadkiem parsuje sie na
 * poprawny rekord. Na to potrzebne sa sumy kontrolne — poza zakresem M5.
 *
 * <p>Nie jest bezpieczny watkowo — silnik zaklada uzycie jednowatkowe.
 */
public final class Wal implements AutoCloseable {

    /** Co ma sie stac z rekordem, zanim {@link #append} wroci. */
    public enum Durability {
        /** flush + fsync — rekord jest na talerzu dysku. Przezywa zanik pradu. */
        SYNC,
        /** Sam flush — rekord jest w cache OS-a. Przezywa pad procesu, nie maszyny. */
        OS_BUFFERED
    }

    private final Path file;
    private final Durability durability;
    private FileDescriptor fd;
    private OutputStream out;

    private Wal(Path file, Durability durability) {
        this.file = file;
        this.durability = durability;
    }

    /** Otwiera log do dopisywania z domyslna trwaloscia. Nie robi replay. */
    public static Wal open(Path file) throws IOException {
        return open(file, Durability.SYNC);
    }

    /** Otwiera log do dopisywania (tworzy plik, jesli nie istnieje). Nie robi replay. */
    public static Wal open(Path file, Durability durability) throws IOException {
        Wal wal = new Wal(file, durability);
        wal.openStream(/*append*/ true);
        return wal;
    }

    /** Dopisuje rekord i wymusza go na dysk zgodnie z {@link Durability}. */
    public void append(Record r) throws IOException {
        Encoding.writeRecord(out, r);
        out.flush();                              // bufor -> OS
        if (durability == Durability.SYNC) {
            fd.sync();                            // OS -> dysk
        }
    }

    /**
     * Odtwarza log: dla kazdego kompletnego rekordu wola {@code sink}, w kolejnosci zapisu.
     * Brak pliku = no-op (swiezy sklep bez historii).
     *
     * <p>Jesli plik konczy sie niekompletnym rekordem, metoda <b>ucina go</b> do ostatniej zdrowej
     * granicy — inaczej kolejne {@code append} dopisywalyby za smieciem i log nie dalby sie juz
     * nigdy odczytac.
     *
     * @return dlugosc zdrowej czesci logu w bajtach
     */
    public static long replay(Path file, Consumer<Record> sink) throws IOException {
        if (!Files.exists(file)) return 0;

        long healthy = 0;
        try (CountingInputStream in =
                     new CountingInputStream(new BufferedInputStream(new FileInputStream(file.toFile())))) {
            while (true) {
                long recordStart = in.count();
                Record r;
                try {
                    r = Encoding.readRecord(in);
                } catch (EOFException e) {
                    healthy = recordStart; // rekord urwany w polowie — koniec zdrowej czesci
                    break;
                }
                if (r == null) {           // czysty koniec pliku
                    healthy = in.count();
                    break;
                }
                sink.accept(r);
            }
        }

        if (Files.size(file) > healthy) {
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                ch.truncate(healthy);
                ch.force(true);
            }
        }
        return healthy;
    }

    /**
     * Kasuje zawartosc logu i zostawia go otwartym do dalszego dopisywania.
     *
     * <p>Wolane po zrzucie memtable do SSTable: skoro te rekordy leza juz trwale w niemutowalnym
     * pliku, log przestaje byc potrzebny i moze zaczac rosnac od zera. Kolejnosc jest istotna —
     * najpierw kompletna SSTable, dopiero potem czyszczenie logu. Crash pomiedzy tymi krokami
     * kosztuje tylko powtorzony replay tych samych rekordow, nigdy ich utrate.
     */
    public void truncate() throws IOException {
        out.flush();
        out.close();
        openStream(/*append*/ false); // otwarcie bez append zeruje plik
        fd.sync();
    }

    @Override
    public void close() throws IOException {
        out.flush();
        out.close();
    }

    private void openStream(boolean append) throws IOException {
        FileOutputStream fos = new FileOutputStream(file.toFile(), append);
        this.fd = fos.getFD();
        this.out = new BufferedOutputStream(fos);
    }

    /** Liczy przeczytane bajty — replay musi wiedziec, gdzie konczy sie ostatni caly rekord. */
    private static final class CountingInputStream extends FilterInputStream {

        private long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = in.read(b, off, len);
            if (read > 0) count += read;
            return read;
        }
    }
}
