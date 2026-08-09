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
 * Write-ahead log — an append-only file every mutation reaches <b>before</b> it becomes visible in
 * the {@link MemTable}. That is what makes it possible to rebuild memory state after a restart or
 * crash via {@link #replay(Path, Consumer)}.
 *
 * <p><b>Durability</b> is controlled by {@link Durability}. The default {@link Durability#SYNC}
 * does a {@code flush} to the OS plus an {@code fsync} ({@link FileDescriptor#sync()}) to disk
 * after every record — slow, but it survives even a power cut, which is the entire point of a WAL.
 * {@link Durability#OS_BUFFERED} skips the fsync: it protects against process death, not machine
 * death.
 *
 * <p><b>Torn tail.</b> A crash halfway through {@link #append} leaves an incomplete record at the
 * end of the file. {@link #replay} treats this as a normal scenario: it yields every complete
 * record, trims the log back to the last healthy boundary and returns that offset. A write that
 * never finished was never acknowledged to the caller, so losing it breaks no promise — whereas
 * blowing up on startup instead would defeat the whole idea of having a log.
 *
 * <p>What this does not catch: corruption <i>inside</i> the file that happens to parse as a valid
 * record. That needs checksums — out of scope for M5.
 *
 * <p>Not thread-safe — the engine assumes single-threaded use.
 */
public final class Wal implements AutoCloseable {

    /** What must happen to a record before {@link #append} returns. */
    public enum Durability {
        /** flush + {@code fsync} — the record is on the platter. Survives power loss. */
        SYNC,
        /** flush only — the record is in the OS cache. Survives process death, not machine death. */
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

    /** Opens the log for appending with the default durability. Does not replay. */
    public static Wal open(Path file) throws IOException {
        return open(file, Durability.SYNC);
    }

    /** Opens the log for appending (creating the file if absent). Does not replay. */
    public static Wal open(Path file, Durability durability) throws IOException {
        Wal wal = new Wal(file, durability);
        wal.openStream(/*append*/ true);
        return wal;
    }

    /** Appends a record and forces it out according to {@link Durability}. */
    public void append(Record r) throws IOException {
        Encoding.writeRecord(out, r);
        out.flush();                              // buffer -> OS
        if (durability == Durability.SYNC) {
            fd.sync();                            // OS -> disk
        }
    }

    /**
     * Replays the log: calls {@code sink} for every complete record, in write order. A missing file
     * is a no-op (a fresh store with no history).
     *
     * <p>If the file ends with an incomplete record, this method <b>truncates it</b> back to the
     * last healthy boundary — otherwise subsequent appends would write past the garbage and the log
     * could never be read again.
     *
     * @return the length of the healthy part of the log, in bytes
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
                    healthy = recordStart; // record cut in half — end of the healthy part
                    break;
                }
                if (r == null) {           // clean end of file
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
     * Clears the log's contents and leaves it open for further appends.
     *
     * <p>Called after a memtable flush to an SSTable: once those records sit durably in an immutable
     * file, the log is no longer needed and can start growing from zero again. The order matters —
     * a complete SSTable first, only then the log cleanup. A crash between those two steps costs
     * only a repeated replay of the same records, never their loss.
     */
    public void truncate() throws IOException {
        out.flush();
        out.close();
        openStream(/*append*/ false); // opening without append zeroes the file
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

    /** Counts bytes read — replay needs to know where the last whole record ends. */
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
