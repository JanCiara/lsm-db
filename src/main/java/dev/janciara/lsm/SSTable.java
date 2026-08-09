package dev.janciara.lsm;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
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
 * Sorted String Table — an <b>immutable</b> file holding records sorted by increasing key. It is
 * produced either by flushing a memtable or by merging several older tables ({@link #compact}), and
 * from that moment on it is only ever read.
 *
 * <p>File layout (version {@value #VERSION}):
 * <pre>
 *   header:  "LSMT" (4B) | version (1B)
 *   data:    record[entryCount]            — {@link Encoding} format, strictly increasing keys,
 *                                            grouped into ~{@value #BLOCK_SIZE_BYTES} B blocks
 *   index:   uvarint(blockCount) | { blob(first key of block) | uvarint(block offset) }*
 *   bloom:   Bloom filter over all keys in the table
 *   footer:  uvarint(entryCount) | uvarint(maxSeqNo) | blob(minKey) | blob(maxKey)
 *            | uvarint(index offset)
 *   trailer: int32BE(footer length) | "LSMT" (4B)
 * </pre>
 *
 * <p>The footer sits at the end because the writer only learns its metadata after streaming through
 * every record — we want neither to buffer everything in memory nor to seek back to the start of
 * the file. The fixed-length trailer (8B) is what makes it findable: read the last 8 bytes, get the
 * footer length from them, and only then the footer itself. The repeated magic at the end is a cheap
 * check that the file was not cut short mid-write.
 *
 * <p><b>A point lookup costs one block, not the whole file.</b> The sieves, in order: the
 * {@code minKey}/{@code maxKey} range, the Bloom filter (a "not here" answer without touching the
 * disk), and finally a binary search over the index pointing at the one block that could hold the
 * key. Index and filter are small, so {@link #open} loads them into memory once and keeps them there.
 *
 * <p><b>Writing is atomic:</b> {@link Writer} creates a {@code .tmp} file, fsyncs it, and only then
 * swaps the name in ({@code ATOMIC_MOVE}). A reader never sees a half-written table — either the
 * file is absent, or it is complete.
 *
 * <p><b>The file channel is opened once</b>, in {@link #open}, and lives until {@link #close}. An
 * earlier version opened the file on every {@code get} — the M5 benchmark showed that {@code open()}
 * alone cost ~85 µs, roughly 99% of read time. Reads go through <i>positional</i>
 * {@code channel.read(buffer, position)} calls, which do not move the channel position, so several
 * cursors can read the same file at once without treading on each other.
 */
public final class SSTable implements Closeable {

    /** Magic at the start and at the very end of the file. */
    static final byte[] MAGIC = {'L', 'S', 'M', 'T'};
    /** 1 = data only (M2). 2 = data + block index + Bloom filter (M4). */
    static final int VERSION = 2;

    /**
     * Target size of a data block. A smaller block means less reading per hit but a bigger index;
     * 4 KiB is the usual compromise (and the page size on most systems).
     */
    static final int BLOCK_SIZE_BYTES = 4096;

    private static final int HEADER_LEN = MAGIC.length + 1;                 // magic + version
    private static final int TAIL_LEN = Integer.BYTES + MAGIC.length;       // footer length + magic

    private final Path file;
    private final FileChannel channel;
    private final long entryCount;
    private final long maxSeqNo;
    private final byte[] minKey;
    private final byte[] maxKey;
    /** End of the data section = start of the index. */
    private final long dataEnd;
    /** First key and offset of every block, sorted by key. */
    private final List<Block> index;
    private final BloomFilter bloom;

    private SSTable(Path file, FileChannel channel, long entryCount, long maxSeqNo,
                    byte[] minKey, byte[] maxKey, long dataEnd, List<Block> index, BloomFilter bloom) {
        this.file = file;
        this.channel = channel;
        this.entryCount = entryCount;
        this.maxSeqNo = maxSeqNo;
        this.minKey = minKey;
        this.maxKey = maxKey;
        this.dataEnd = dataEnd;
        this.index = index;
        this.bloom = bloom;
    }

    /** An index entry: which byte a block starts at, and what its first key is. */
    private record Block(byte[] firstKey, long offset) {}

    // ---- writing -------------------------------------------------------------

    /**
     * Writes records into a new table at {@code file} and returns a handle ready for reading.
     *
     * @param sorted records sorted by increasing (unsigned) key, without duplicates — exactly what
     *               {@link MemTable#snapshot()} yields. Breaking the order is a programmer error,
     *               so it ends in {@link IllegalArgumentException} rather than silent file corruption.
     */
    public static SSTable write(Path file, Collection<Record> sorted) throws IOException {
        if (sorted.isEmpty()) throw new IllegalArgumentException("an empty SSTable makes no sense");
        try (Writer writer = writer(file)) {
            for (Record r : sorted) {
                writer.add(r);
            }
            return writer.finish().orElseThrow();
        }
    }

    /** Opens a writer for building a table in streaming fashion — used by flush and by compaction. */
    public static Writer writer(Path file) throws IOException {
        return new Writer(file);
    }

    /**
     * Builds a table record by record, without holding the whole thing in memory.
     *
     * <p>Lifecycle: {@link #add} n times, then {@link #finish}. A writer closed without
     * {@code finish} (an exception in the middle of a merge) cleans up its own {@code .tmp} file —
     * which is why it belongs in {@code try-with-resources}.
     */
    public static final class Writer implements Closeable {

        private final Path file;
        private final Path tmp;
        private final FileOutputStream fos;
        private final CountingOutputStream out;

        private final List<Block> blocks = new ArrayList<>();
        private long bytesInBlock;
        /** Key hashes for the Bloom filter — 8 B per key; the filter itself is built in {@link #finish}. */
        private long[] keyHashes = new long[64];
        private int keyCount;

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
                this.out = new CountingOutputStream(new BufferedOutputStream(fos));
                out.write(MAGIC);
                out.write(VERSION);
            } catch (IOException e) {
                fos.close();
                Files.deleteIfExists(tmp);
                throw e;
            }
        }

        /** Appends a record; keys must be strictly increasing (unsigned). */
        public void add(Record r) throws IOException {
            if (closed) throw new IllegalStateException("writer already closed");
            if (prevKey != null && Arrays.compareUnsigned(prevKey, r.key()) >= 0) {
                throw new IllegalArgumentException(
                        "records must be sorted in increasing order with no duplicate keys");
            }
            if (minKey == null) minKey = r.key();
            prevKey = r.key();

            // A new block starts on a record boundary — a record is never cut in half, which is
            // what lets a reader jump to an offset from the index and start decoding right away.
            if (blocks.isEmpty() || bytesInBlock >= BLOCK_SIZE_BYTES) {
                blocks.add(new Block(r.key(), out.count()));
                bytesInBlock = 0;
            }

            long before = out.count();
            Encoding.writeRecord(out, r);
            bytesInBlock += out.count() - before;

            rememberKey(r.key());
            entryCount++;
            if (r.seqNo() > maxSeqNo) maxSeqNo = r.seqNo();
        }

        /**
         * Seals the file: index, Bloom filter, footer, fsync, atomic rename.
         *
         * @return an empty Optional when not a single record was added — then no file is created at
         *         all. That is how a merge ends when everything in it turned out to be a tombstone.
         */
        public Optional<SSTable> finish() throws IOException {
            if (closed) throw new IllegalStateException("writer already closed");
            closed = true;

            if (entryCount == 0) {
                out.close();
                Files.deleteIfExists(tmp);
                return Optional.empty();
            }

            long indexOffset = out.count();
            Encoding.writeUVarLong(out, blocks.size());
            for (Block block : blocks) {
                Encoding.writeBlob(out, block.firstKey());
                Encoding.writeUVarLong(out, block.offset());
            }

            BloomFilter bloom = BloomFilter.build(keyHashes, keyCount);
            bloom.writeTo(out);

            byte[] footer = buildFooter(entryCount, maxSeqNo, minKey, prevKey, indexOffset);
            out.write(footer);
            out.write(intBE(footer.length));
            out.write(MAGIC);

            out.flush();        // buffer -> OS
            fos.getFD().sync(); // OS -> disk; only now does swapping the name guarantee anything
            out.close();

            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            syncDir(file.getParent());
            return Optional.of(new SSTable(file, FileChannel.open(file, StandardOpenOption.READ),
                    entryCount, maxSeqNo, minKey, prevKey, indexOffset, List.copyOf(blocks), bloom));
        }

        /** A no-op after {@link #finish}; otherwise deletes the unfinished temporary file. */
        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            out.close();
            Files.deleteIfExists(tmp);
        }

        private void rememberKey(byte[] key) {
            if (keyCount == keyHashes.length) {
                keyHashes = Arrays.copyOf(keyHashes, keyHashes.length * 2);
            }
            keyHashes[keyCount++] = BloomFilter.hash(key);
        }
    }

    private static byte[] buildFooter(long entryCount, long maxSeqNo, byte[] minKey, byte[] maxKey,
                                      long indexOffset) {
        var bos = new ByteArrayOutputStream();
        try {
            Encoding.writeUVarLong(bos, entryCount);
            Encoding.writeUVarLong(bos, maxSeqNo);
            Encoding.writeBlob(bos, minKey);
            Encoding.writeBlob(bos, maxKey);
            Encoding.writeUVarLong(bos, indexOffset);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream never actually throws
        }
        return bos.toByteArray();
    }

    // ---- merging -------------------------------------------------------------

    /**
     * Merges several tables into one, keeping only the freshest version of each key.
     *
     * <p>A textbook k-way merge: a priority queue holds one cursor per table, ordered by current key
     * ascending and, on ties, by table recency descending. That way the first record pulled for a
     * given key is the winner, and every subsequent one with the same key is a stale version to
     * discard. Memory holds only k records rather than the whole data set — which is why merging
     * goes through {@link Cursor} and {@link Writer} rather than lists.
     *
     * @param inputs         tables ordered <b>oldest to newest</b> — that order breaks the ties
     * @param dropTombstones whether to discard tombstones instead of copying them through. This is
     *        allowed <b>only</b> when {@code inputs} also covers the oldest table in the store: a
     *        tombstone exists to hide an older value, so removing it too early resurrects a deleted
     *        key. When merging everything, there is nothing left underneath to hide.
     * @return the new table, or an empty Optional when the merge left no records at all
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

                // The same key in older tables — skip past it, do not write it.
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
                    // read-only streams — a failure to close does not change the merge result
                }
            }
        }
    }

    /** Advances a cursor and puts it back into the queue, provided anything is left. */
    private static void step(Source source, PriorityQueue<Source> queue) throws IOException {
        source.cursor.advance();
        if (source.cursor.peek() != null) queue.add(source);
    }

    /** A cursor plus its place in the recency order ({@code rank} grows as tables get newer). */
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

    // ---- reading -------------------------------------------------------------

    /** Opens an existing table: validates the header, loads the footer, index and Bloom filter. */
    public static SSTable open(Path file) throws IOException {
        long size = Files.size(file);
        if (size < HEADER_LEN + TAIL_LEN) {
            throw new IOException("file too short to be an SSTable: " + file);
        }

        FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
        try {
            byte[] header = readAt(ch, 0, HEADER_LEN);
            if (!Arrays.equals(MAGIC, Arrays.copyOf(header, MAGIC.length))) {
                throw new IOException("not an SSTable (bad magic): " + file);
            }
            int version = header[MAGIC.length] & 0xFF;
            if (version != VERSION) {
                throw new IOException("unsupported SSTable version " + version + " in " + file);
            }

            byte[] tail = readAt(ch, size - TAIL_LEN, TAIL_LEN);
            if (!Arrays.equals(MAGIC, Arrays.copyOfRange(tail, Integer.BYTES, TAIL_LEN))) {
                throw new IOException("no magic at the end — file truncated or corrupt: " + file);
            }
            int footerLen = ByteBuffer.wrap(tail, 0, Integer.BYTES).getInt();
            long footerStart = size - TAIL_LEN - footerLen;
            if (footerLen < 0 || footerStart < HEADER_LEN) {
                throw new IOException("bad footer length (" + footerLen + ") in " + file);
            }

            var footer = new ByteArrayInputStream(readAt(ch, footerStart, footerLen));
            long entryCount = Encoding.readUVarLong(footer);
            long maxSeqNo = Encoding.readUVarLong(footer);
            byte[] minKey = Encoding.readBlob(footer);
            byte[] maxKey = Encoding.readBlob(footer);
            long indexOffset = Encoding.readUVarLong(footer);
            if (indexOffset < HEADER_LEN || indexOffset > footerStart) {
                throw new IOException("bad index offset (" + indexOffset + ") in " + file);
            }

            // Index and filter are small compared to the data — we keep them in memory for good.
            var meta = new ByteArrayInputStream(
                    readAt(ch, indexOffset, Math.toIntExact(footerStart - indexOffset)));
            List<Block> index = readIndex(meta, indexOffset);
            BloomFilter bloom = BloomFilter.readFrom(meta);
            return new SSTable(file, ch, entryCount, maxSeqNo, minKey, maxKey, indexOffset, index, bloom);
        } catch (IOException | RuntimeException e) {
            ch.close(); // the channel outlives this method only if the table really opened
            throw e;
        }
    }

    private static List<Block> readIndex(InputStream in, long indexOffset) throws IOException {
        int blockCount = Math.toIntExact(Encoding.readUVarLong(in));
        if (blockCount <= 0) throw new IOException("index with no blocks");
        var blocks = new ArrayList<Block>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            byte[] firstKey = Encoding.readBlob(in);
            long offset = Encoding.readUVarLong(in);
            if (offset < HEADER_LEN || offset > indexOffset) {
                throw new IOException("block " + i + " points outside the data (offset " + offset + ")");
            }
            blocks.add(new Block(firstKey, offset));
        }
        return List.copyOf(blocks);
    }

    /**
     * Looks up the record with exactly this key. Returns the whole {@link Record} — possibly a
     * tombstone, because the table is immutable and a "deletion" lives inside it as an ordinary
     * entry carrying a marker.
     *
     * <p>Three sieves before touching the data: the key range, the Bloom filter, the index. At most
     * one block is scanned — the one whose first key is the last that is not greater than the key
     * being looked up.
     */
    public Optional<Record> get(byte[] key) throws IOException {
        if (!mightContain(key)) return Optional.empty();

        int block = blockFor(key);
        if (block < 0) return Optional.empty(); // key sorts before the first block
        long from = index.get(block).offset();
        long to = block + 1 < index.size() ? index.get(block + 1).offset() : dataEnd;

        try (Cursor cursor = cursorOver(from, to)) {
            Record r;
            while ((r = cursor.peek()) != null) {
                int cmp = Arrays.compareUnsigned(r.key(), key);
                if (cmp == 0) return Optional.of(r);
                if (cmp > 0) return Optional.empty(); // keys ascend — it cannot appear later
                cursor.advance();
            }
        }
        return Optional.empty();
    }

    /**
     * The cheap sieve before reading from disk: the key range from the footer plus the Bloom filter.
     * {@code false} is certain, {@code true} needs verifying in the file.
     */
    public boolean mightContain(byte[] key) {
        if (Arrays.compareUnsigned(key, minKey) < 0 || Arrays.compareUnsigned(key, maxKey) > 0) {
            return false;
        }
        return bloom.mightContain(key);
    }

    /** The last block whose first key is not greater than the given key; -1 when there is none. */
    private int blockFor(byte[] key) {
        int lo = 0;
        int hi = index.size() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (Arrays.compareUnsigned(index.get(mid).firstKey(), key) <= 0) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** Every record in increasing key order — handy in tests, expensive in memory. */
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

    /** A cursor over the whole table, positioned on the first record. The caller closes it. */
    public Cursor cursor() throws IOException {
        return cursorOver(HEADER_LEN, dataEnd);
    }

    private Cursor cursorOver(long from, long to) throws IOException {
        Cursor cursor = new Cursor(dataStream(from, to));
        try {
            cursor.advance();
            return cursor;
        } catch (IOException e) {
            cursor.close();
            throw e;
        }
    }

    /**
     * A one-way walk over records. {@link #peek} is separate from {@link #advance} because merging
     * has to <i>compare</i> the current keys of all sources before deciding which of them to move.
     */
    public final class Cursor implements Closeable {

        private final InputStream in;
        private Record current;

        private Cursor(InputStream in) {
            this.in = in;
        }

        /** The current record, or {@code null} once the range ends. Does not touch the disk. */
        public Record peek() {
            return current;
        }

        public void advance() throws IOException {
            current = Encoding.readRecord(in);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** Closes the file channel. The table stops being readable. */
    @Override
    public void close() throws IOException {
        channel.close();
    }

    /** Closes and deletes the table's file. Mind the order — see {@code LsmStore#compact}. */
    public void delete() throws IOException {
        close();
        Files.delete(file);
    }

    public Path path() {
        return file;
    }

    public long entryCount() {
        return entryCount;
    }

    /** Number of data blocks = number of index entries. */
    public int blockCount() {
        return index.size();
    }

    /** Highest {@code seqNo} in the table — {@link LsmStore} resumes its counter from it. */
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
        return "SSTable[" + file.getFileName() + ", " + entryCount + " records, "
                + index.size() + " blocks]";
    }

    // ---- internals -----------------------------------------------------------

    /** A stream covering exactly the byte range {@code [from, to)} of the file. */
    private InputStream dataStream(long from, long to) {
        return new BufferedInputStream(new ChannelRangeInputStream(channel, from, to));
    }

    private static byte[] readAt(FileChannel ch, long position, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        while (buf.hasRemaining()) {
            if (ch.read(buf, position + buf.position()) < 0) {
                throw new EOFException("unexpected end of file at offset " + position);
            }
        }
        return buf.array();
    }

    private static byte[] intBE(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    /**
     * fsyncs a directory so that a rename or a delete survives an OS crash. Windows does not allow
     * opening a directory as a file — there we simply skip it (we rely on rename being atomic anyway).
     */
    static void syncDir(Path dir) {
        if (dir == null) return;
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException e) {
            // Windows: AccessDeniedException — no API for fsyncing a directory.
        }
    }

    /** Counts bytes handed to the stream — the writer needs offsets for the index. */
    private static final class CountingOutputStream extends FilterOutputStream {

        private long count;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        long count() {
            return count;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len); // FilterOutputStream would otherwise write byte by byte
            count += len;
        }
    }

    /**
     * Reads only the range {@code [from, to)} from a channel, keeping its own position.
     *
     * <p>Two things at once: (1) the range limit makes a cursor over a block end exactly on the
     * block boundary and never start decoding the index section as a record; (2) using
     * <i>positional</i> {@code read(buf, position)} does not move the shared channel's position, so
     * several streams can read the same file independently.
     */
    private static final class ChannelRangeInputStream extends InputStream {

        private final FileChannel channel;
        private long position;
        private long remaining;

        ChannelRangeInputStream(FileChannel channel, long from, long to) {
            this.channel = channel;
            this.position = from;
            this.remaining = Math.max(0, to - from);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            ByteBuffer buf = ByteBuffer.wrap(b, off, (int) Math.min(len, remaining));
            int read = channel.read(buf, position);
            if (read <= 0) return -1;
            position += read;
            remaining -= read;
            return read;
        }

        @Override
        public int available() {
            return (int) Math.min(Integer.MAX_VALUE, remaining);
        }
    }
}
