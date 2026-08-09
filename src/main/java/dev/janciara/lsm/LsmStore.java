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
 * The {@link KVStore} implementation: WAL + memtable + immutable SSTables + table merging.
 *
 * <p><b>Write path.</b> Every {@code put}/{@code delete} first appends a record to the {@link Wal}
 * (durability), and only then makes it visible in the {@link MemTable} (visibility). Once the
 * memtable crosses its size threshold, its contents land in a new {@link SSTable}, the memtable
 * starts empty, and the WAL is cleared.
 *
 * <p><b>Read path</b> walks from the freshest layer to the oldest: memtable, then SSTables newest
 * to oldest. A table that does not hold the key usually answers without reading any data — the key
 * range or the Bloom filter rejects it ({@link SSTable#mightContain}). The first hit wins; there is
 * no need to compare {@code seqNo}, because every flush contains records newer than everything
 * flushed before it. The record found may be a tombstone, in which case the key is deleted as far
 * as the world is concerned, even though an older table still holds a value for it. That is exactly
 * why a deletion can be a write.
 *
 * <p><b>Recovery after a restart.</b> {@link #open} loads the metadata of every {@code sst-*.sst}
 * file, replays the WAL into the memtable and sets the {@code seqNo} counter to
 * {@code max(seqNo across SSTables and the log) + 1}. A crash between writing an SSTable and
 * clearing the WAL is harmless: replay returns records that are already on disk, and they land in
 * the memtable — the winning layer. No read sees an older value because of it.
 *
 * <p><b>Compaction.</b> Without merging, the number of tables would grow without bound and an
 * overwritten or deleted key would occupy space forever — a tombstone is a write too, after all.
 * Once the table count crosses the threshold, {@link #compact()} merges them all into one, keeping
 * a single freshest version of each key and discarding tombstones.
 *
 * <p>Not thread-safe — the class assumes single-threaded use.
 */
public final class LsmStore implements KVStore {

    private static final String WAL_FILE = "wal.log";
    private static final String SST_PREFIX = "sst-";
    private static final String SST_SUFFIX = ".sst";
    private static final String TMP_SUFFIX = ".tmp";

    /** Default memtable size at which it is flushed to disk. */
    public static final long DEFAULT_FLUSH_THRESHOLD_BYTES = 4L * 1024 * 1024;
    /** Default number of tables that triggers a merge. */
    public static final int DEFAULT_COMPACTION_TRIGGER = 4;

    private final Path dir;
    private final MemTable memtable;
    private final Wal wal;
    /** Oldest to newest — reads scan this list from the end. */
    private final List<SSTable> sstables;
    private final Options options;
    private long nextSeqNo;
    private long nextTableNumber;

    private LsmStore(Path dir, MemTable memtable, Wal wal, List<SSTable> sstables,
                     Options options, long nextSeqNo, long nextTableNumber) {
        this.dir = dir;
        this.memtable = memtable;
        this.wal = wal;
        this.sstables = sstables;
        this.options = options;
        this.nextSeqNo = nextSeqNo;
        this.nextTableNumber = nextTableNumber;
    }

    /**
     * Engine settings. Gathered into one object, because otherwise {@code open} would take several
     * parameters of the same type side by side — an API that invites swapped arguments.
     *
     * @param flushThresholdBytes memtable size at which it gets flushed to an SSTable
     * @param compactionTrigger   number of tables that triggers a merge
     * @param durability          what must happen to a WAL record before {@code put} returns
     */
    public record Options(long flushThresholdBytes, int compactionTrigger, Wal.Durability durability) {

        public Options {
            if (flushThresholdBytes <= 0) {
                throw new IllegalArgumentException("flush threshold must be positive");
            }
            if (compactionTrigger < 2) {
                throw new IllegalArgumentException("merging only makes sense from 2 tables up");
            }
            if (durability == null) {
                throw new IllegalArgumentException("durability must be given");
            }
        }

        public static Options defaults() {
            return new Options(DEFAULT_FLUSH_THRESHOLD_BYTES, DEFAULT_COMPACTION_TRIGGER,
                    Wal.Durability.SYNC);
        }

        public Options withFlushThresholdBytes(long bytes) {
            return new Options(bytes, compactionTrigger, durability);
        }

        public Options withCompactionTrigger(int tables) {
            return new Options(flushThresholdBytes, tables, durability);
        }

        public Options withDurability(Wal.Durability durability) {
            return new Options(flushThresholdBytes, compactionTrigger, durability);
        }
    }

    /** Opens (or creates) a store in directory {@code dir} with default settings. */
    public static LsmStore open(Path dir) {
        return open(dir, Options.defaults());
    }

    /**
     * Opens (or creates) a store in directory {@code dir}. Small thresholds force frequent flushes
     * and merges — handy in tests and when playing with the shape of the tree.
     */
    public static LsmStore open(Path dir, Options options) {
        try {
            Files.createDirectories(dir);
            deleteLeftoverTempFiles(dir);

            List<SSTable> sstables = openSSTables(dir);
            long maxSeqNo = -1L; // -1 => no history => nextSeqNo starts at 0
            long maxTableNumber = -1L;
            for (SSTable t : sstables) {
                maxSeqNo = Math.max(maxSeqNo, t.maxSeqNo());
                maxTableNumber = Math.max(maxTableNumber, tableNumber(t.path()));
            }

            MemTable memtable = new MemTable();
            long[] seqNo = {maxSeqNo};
            // Replay trims any torn tail itself, so the open() below appends after the last
            // complete record rather than after half a record left over from a crash.
            Wal.replay(dir.resolve(WAL_FILE), r -> {
                memtable.put(r);
                if (r.seqNo() > seqNo[0]) seqNo[0] = r.seqNo();
            });

            Wal wal = Wal.open(dir.resolve(WAL_FILE), options.durability());
            return new LsmStore(dir, memtable, wal, sstables, options,
                    seqNo[0] + 1, maxTableNumber + 1);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open the store in " + dir, e);
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
     * Flushes the memtable into a new SSTable and clears the WAL. An empty memtable is a no-op (we
     * do not create empty files). Normally this fires on its own once the threshold is crossed;
     * calling it by hand is useful in tests and when closing a store with a "clean" log.
     */
    public void flush() {
        if (memtable.isEmpty()) return;
        try {
            sstables.add(SSTable.write(nextTablePath(), memtable.snapshot()));
            memtable.clear();
            wal.truncate();
        } catch (IOException e) {
            throw new UncheckedIOException("flushing the memtable to an SSTable failed", e);
        }
        if (sstables.size() >= options.compactionTrigger()) compact();
    }

    /**
     * Merges all SSTables into one: every key survives in its freshest version, and tombstones
     * disappear entirely. Only this reclaims space taken by overwrites and deletions — until then
     * every write, {@code delete} included, only grows the set of files. A no-op below two tables.
     *
     * <p><b>Crash safety</b> rests on two things, and both are worth understanding:
     *
     * <p>1. The merged table gets a <b>higher number</b> than any of its inputs, so from the moment
     * it appears on disk it shadows them on the read path. A crash after writing it but before
     * deleting the sources leaves only dead files — never bad data.
     *
     * <p>2. Sources are deleted <b>oldest-first</b>. This is not cosmetic: were it the other way
     * round, a crash could take away a tombstone while leaving an older value of the same key
     * underneath — and the deleted key would come back from the dead. Deleting from the bottom up
     * guarantees that the set of surviving tables is always a <i>trailing</i> stretch of history,
     * one in which the freshest record of every key is still present.
     */
    public void compact() {
        if (sstables.size() < 2) return;
        try {
            List<SSTable> inputs = List.copyOf(sstables);
            // We merge the complete set, so nothing is left underneath for a tombstone to hide.
            Optional<SSTable> merged = SSTable.compact(nextTablePath(), inputs, /*dropTombstones*/ true);

            for (SSTable stale : inputs) { // list order = oldest first
                stale.delete();            // delete() closes the channel before removing the file
            }
            SSTable.syncDir(dir);

            sstables.clear();
            merged.ifPresent(sstables::add);
        } catch (IOException e) {
            throw new UncheckedIOException("merging SSTables failed", e);
        }
    }

    /** Number of SSTables on disk — a peek at engine state (tests, future metrics). */
    public int sstableCount() {
        return sstables.size();
    }

    @Override
    public void close() {
        try {
            wal.close();
            for (SSTable table : sstables) {
                table.close(); // every table holds an open channel from the moment it is opened
            }
            sstables.clear();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to close the store", e);
        }
    }

    // ---- internals -----------------------------------------------------------

    /** WAL first (durability), memtable second (visibility), then a flush if one is due. */
    private void writeAhead(Record r) {
        try {
            wal.append(r);
        } catch (IOException e) {
            throw new UncheckedIOException("writing to the WAL failed", e);
        }
        memtable.put(r);
        if (memtable.sizeInBytes() >= options.flushThresholdBytes()) flush();
    }

    /** Path of the next table; the number grows with every flush and every merge. */
    private Path nextTablePath() {
        return dir.resolve(String.format("%s%06d%s", SST_PREFIX, nextTableNumber++, SST_SUFFIX));
    }

    /** Scans tables newest-first — the first hit is by definition the freshest. */
    private Optional<Record> searchSSTables(byte[] key) {
        for (int i = sstables.size() - 1; i >= 0; i--) {
            SSTable table = sstables.get(i);
            try {
                Optional<Record> found = table.get(key);
                if (found.isPresent()) return found;
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read " + table.path(), e);
            }
        }
        return Optional.empty();
    }

    /** Loads table metadata from the directory, sorted by ascending number (i.e. oldest first). */
    private static List<SSTable> openSSTables(Path dir) throws IOException {
        var files = new ArrayList<Path>();
        try (DirectoryStream<Path> ls = Files.newDirectoryStream(dir, SST_PREFIX + "*" + SST_SUFFIX)) {
            ls.forEach(files::add);
        }
        files.sort((a, b) -> Long.compare(tableNumber(a), tableNumber(b)));

        var tables = new ArrayList<SSTable>(files.size());
        try {
            for (Path f : files) {
                tables.add(SSTable.open(f));
            }
        } catch (IOException | RuntimeException e) {
            for (SSTable opened : tables) { // do not leave channels open after a failed startup
                try {
                    opened.close();
                } catch (IOException ignored) {
                    // we are throwing the original error anyway
                }
            }
            throw e;
        }
        return tables;
    }

    /**
     * The number from a name like {@code sst-000007.sst}. It grows with every flush, so name order
     * is data-freshness order. A name that does not match the pattern fails the open instead of
     * quietly putting tables in the wrong order.
     */
    private static long tableNumber(Path file) {
        String name = file.getFileName().toString();
        String digits = name.substring(SST_PREFIX.length(), name.length() - SST_SUFFIX.length());
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("unexpected SSTable file name: " + name, e);
        }
    }

    /** Unfinished writes from a previous life of the process — safe to delete. */
    private static void deleteLeftoverTempFiles(Path dir) throws IOException {
        try (DirectoryStream<Path> ls = Files.newDirectoryStream(dir, "*" + TMP_SUFFIX)) {
            for (Path f : ls) {
                Files.deleteIfExists(f);
            }
        }
    }
}
