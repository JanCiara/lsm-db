package dev.janciara.lsm;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Write-ahead log — append-only plik, do ktorego kazda mutacja trafia <b>zanim</b> zostanie
 * uwidoczniona w {@link MemTable}. Dzieki temu po restarcie/crashu mozna odtworzyc stan pamieci
 * przez {@link #replay(Path, Consumer)}.
 *
 * <p><b>Trwalosc:</b> {@link #append} po kazdym rekordzie robi {@code flush} bufora do OS oraz
 * {@code fsync} ({@link FileDescriptor#sync()}) na dysk. To wolne, ale gwarantuje przetrwanie
 * nawet crashu OS/zaniku pradu — czyli caly sens WAL. Konfigurowalny, luzniejszy tryb dojdzie
 * przy benchmarkach (M5).
 *
 * <p>Rekordy serializowane sa formatem z {@link Encoding}. {@link #replay} czyta az do czystego
 * EOF ({@code readRecord} zwraca {@code null}). Urwany ostatni rekord (np. crash w polowie zapisu)
 * spowoduje {@link java.io.EOFException} — tolerancja „urwanego ogona" to hardening na pozniej.
 *
 * <p>Nie jest bezpieczny watkowo — M1 zaklada uzycie jednowatkowe.
 */
public final class Wal implements AutoCloseable {

    private final FileOutputStream fos;
    private final FileDescriptor fd;
    private final OutputStream out;

    private Wal(FileOutputStream fos, FileDescriptor fd, OutputStream out) {
        this.fos = fos;
        this.fd = fd;
        this.out = out;
    }

    /** Otwiera log do dopisywania (tworzy plik, jesli nie istnieje). Nie robi replay. */
    public static Wal open(Path file) throws IOException {
        FileOutputStream fos = new FileOutputStream(file.toFile(), /*append*/ true);
        return new Wal(fos, fos.getFD(), new BufferedOutputStream(fos));
    }

    /** Dopisuje rekord i wymusza go na dysk (flush + fsync). */
    public void append(Record r) throws IOException {
        Encoding.writeRecord(out, r);
        out.flush();   // bufor -> OS
        fd.sync();     // OS -> dysk
    }

    /**
     * Odtwarza log: dla kazdego zapisanego rekordu wola {@code sink}, w kolejnosci zapisu.
     * Brak pliku = no-op (swiezy sklep bez historii).
     */
    public static void replay(Path file, Consumer<Record> sink) throws IOException {
        if (!Files.exists(file)) return;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file.toFile()))) {
            Record r;
            while ((r = Encoding.readRecord(in)) != null) {
                sink.accept(r);
            }
        }
    }

    @Override
    public void close() throws IOException {
        out.flush();
        out.close();
    }
}
