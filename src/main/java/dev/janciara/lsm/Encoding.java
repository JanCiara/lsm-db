package dev.janciara.lsm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.OptionalLong;

/**
 * Serialisation of records to and from byte streams.
 *
 * <p>On-disk format of a single record:
 * <pre>
 *   uvarint(keyLen) | key[keyLen] | byte(tombstone) | uvarint(seqNo) | uvarint(valLen) | value[valLen]
 * </pre>
 *
 * <p>Numbers are encoded as <b>unsigned LEB128 varints</b> — small values (typical lengths, early
 * seqNos) take 1 byte instead of 8. Protobuf and LevelDB use the same encoding.
 *
 * <p>The streaming API ({@link #writeRecord}/{@link #readRecord}) is deliberate: the WAL appends
 * records one after another, and replay reads them until EOF ({@code readRecord} returns
 * {@code null} at a clean end of stream).
 */
public final class Encoding {

    /**
     * Upper bound on key and value length (64 MiB).
     *
     * <p>This is not about saving space; it is about stopping a corrupted varint from becoming
     * {@code new byte[2_000_000_000]}. Without the bound, a single flipped bit in a file ends in an
     * OutOfMemoryError instead of a readable error — the difference between "the file is broken"
     * and "the process died". The limit applies symmetrically on write and on read.
     */
    public static final int MAX_BLOB_LENGTH = 64 * 1024 * 1024;

    private Encoding() {}

    // ---- varint -------------------------------------------------------------

    /** Writes a number as unsigned LEB128 (7 bits per byte, MSB = "another byte follows"). */
    public static void writeUVarLong(OutputStream out, long value) throws IOException {
        // treated as unsigned: ~0x7F masks off everything but the low seven bits
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) (value & 0x7F));
    }

    /** Reads a varint. Throws {@link EOFException} if the stream ends, including before byte 1. */
    public static long readUVarLong(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("unexpected EOF at start of varint");
        return readUVarLongBody(in, b);
    }

    /**
     * Like {@link #readUVarLong}, but tolerates a clean end of stream: returns an empty
     * {@link OptionalLong} when {@code in} ended before the first byte. Used by
     * {@link #readRecord} to detect the end of a log or file.
     *
     * <p>Important: EOF is signalled by the Optional being <i>empty</i>, not by a value — so a
     * varint decoding to -1L (unsigned max) is never mistaken for the end of the stream.
     */
    private static OptionalLong readUVarLongOrEof(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) return OptionalLong.empty();
        return OptionalLong.of(readUVarLongBody(in, b));
    }

    /** Decodes the rest of a varint given its first byte. */
    private static long readUVarLongBody(InputStream in, int firstByte) throws IOException {
        long result = firstByte & 0x7F;
        if ((firstByte & 0x80) == 0) return result;
        int shift = 7;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException("unexpected EOF in varint");
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift >= 64) throw new IOException("varint too long (corrupt stream)");
        }
    }

    // ---- length-prefixed blob ----------------------------------------------

    public static void writeBlob(OutputStream out, byte[] b) throws IOException {
        if (b.length > MAX_BLOB_LENGTH) {
            throw new IllegalArgumentException(
                    "blob longer than the " + MAX_BLOB_LENGTH + " B limit: " + b.length);
        }
        writeUVarLong(out, b.length);
        out.write(b);
    }

    public static byte[] readBlob(InputStream in) throws IOException {
        return readBytes(in, readUVarLong(in), "blob");
    }

    /**
     * Reads {@code len} bytes, but first checks whether that length is believable at all. A varint
     * can decode to a negative number (bit 63 set) or an absurdly large one — either means a
     * corrupted file, not a reason to allocate.
     */
    private static byte[] readBytes(InputStream in, long len, String what) throws IOException {
        if (len < 0 || len > MAX_BLOB_LENGTH) {
            throw new IOException("implausible length for field " + what + " (" + len + ") — corrupt file");
        }
        byte[] b = new byte[(int) len];
        int read = in.readNBytes(b, 0, (int) len);
        if (read != len) throw new EOFException("truncated " + what + ": wanted " + len + ", got " + read);
        return b;
    }

    // ---- record -------------------------------------------------------------

    public static void writeRecord(OutputStream out, Record r) throws IOException {
        writeBlob(out, r.key());
        out.write(r.tombstone() ? 1 : 0);
        writeUVarLong(out, r.seqNo());
        writeBlob(out, r.value());
    }

    /** Reads one record. Returns {@code null} when the stream ended cleanly (no further record). */
    public static Record readRecord(InputStream in) throws IOException {
        OptionalLong keyLen = readUVarLongOrEof(in);
        if (keyLen.isEmpty()) return null; // clean end of stream
        byte[] key = readBytes(in, keyLen.getAsLong(), "key");

        int tomb = in.read();
        if (tomb < 0) throw new EOFException("truncated record (no tombstone flag)");

        long seqNo = readUVarLong(in);
        byte[] value = readBlob(in);
        return new Record(key, value, tomb != 0, seqNo);
    }

    // ---- convenience (whole record <-> byte[]) ------------------------------

    public static byte[] serialize(Record r) {
        var bos = new ByteArrayOutputStream();
        try {
            writeRecord(bos, r);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream never actually throws
        }
        return bos.toByteArray();
    }

    public static Record deserialize(byte[] data) {
        try {
            Record r = readRecord(new ByteArrayInputStream(data));
            if (r == null) throw new IllegalArgumentException("empty record data");
            return r;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
