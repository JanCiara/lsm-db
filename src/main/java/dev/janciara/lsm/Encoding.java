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
 * Serializacja rekordow do/z strumieni bajtow.
 *
 * <p>Format jednego rekordu na dysku:
 * <pre>
 *   uvarint(keyLen) | key[keyLen] | byte(tombstone) | uvarint(seqNo) | uvarint(valLen) | value[valLen]
 * </pre>
 *
 * <p>Liczby koduje sie jako <b>unsigned LEB128 varint</b> — male wartosci (typowe dlugosci,
 * poczatkowe seqNo) zajmuja 1 bajt zamiast 8. To samo kodowanie uzywa Protobuf i LevelDB.
 *
 * <p>API strumieniowe ({@link #writeRecord}/{@link #readRecord}) jest celowe: WAL (M1) bedzie
 * dopisywal rekordy jeden po drugim, a replay przeczyta je az do EOF ({@code readRecord}
 * zwraca {@code null} na czystym koncu strumienia).
 */
public final class Encoding {

    private Encoding() {}

    // ---- varint -------------------------------------------------------------

    /** Zapis liczby jako unsigned LEB128 (7 bitow na bajt, MSB = "jest kolejny bajt"). */
    public static void writeUVarLong(OutputStream out, long value) throws IOException {
        // traktujemy jako unsigned: ~0x7F maskuje wszystkie bity poza dolna siodemka
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) (value & 0x7F));
    }

    /** Odczyt varinta. Rzuca {@link EOFException} jesli strumien sie urwie (takze przed 1. bajtem). */
    public static long readUVarLong(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("unexpected EOF at start of varint");
        return readUVarLongBody(in, b);
    }

    /**
     * Jak {@link #readUVarLong}, ale toleruje czysty koniec strumienia: zwraca pusty
     * {@link OptionalLong} gdy {@code in} skonczyl sie przed 1. bajtem. Uzywane przez
     * {@link #readRecord} do wykrycia konca logu/pliku.
     *
     * <p>Wazne: EOF jest sygnalizowany przez <i>pustosc</i> Optionala, nie przez wartosc —
     * dzieki temu varint dekodujacy sie do -1L (unsigned max) nie jest mylony z koncem strumienia.
     */
    private static OptionalLong readUVarLongOrEof(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) return OptionalLong.empty();
        return OptionalLong.of(readUVarLongBody(in, b));
    }

    /** Dekoduje reszte varinta majac juz pierwszy bajt. */
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
        writeUVarLong(out, b.length);
        out.write(b);
    }

    public static byte[] readBlob(InputStream in) throws IOException {
        int len = Math.toIntExact(readUVarLong(in));
        byte[] b = new byte[len];
        int read = in.readNBytes(b, 0, len);
        if (read != len) throw new EOFException("truncated blob: wanted " + len + ", got " + read);
        return b;
    }

    // ---- record -------------------------------------------------------------

    public static void writeRecord(OutputStream out, Record r) throws IOException {
        writeBlob(out, r.key());
        out.write(r.tombstone() ? 1 : 0);
        writeUVarLong(out, r.seqNo());
        writeBlob(out, r.value());
    }

    /** Odczyt jednego rekordu. Zwraca {@code null} gdy strumien skonczyl sie czysto (brak kolejnego rekordu). */
    public static Record readRecord(InputStream in) throws IOException {
        OptionalLong keyLen = readUVarLongOrEof(in);
        if (keyLen.isEmpty()) return null; // czysty koniec strumienia
        int kLen = Math.toIntExact(keyLen.getAsLong());
        byte[] key = new byte[kLen];
        if (in.readNBytes(key, 0, kLen) != kLen) throw new EOFException("truncated key");

        int tomb = in.read();
        if (tomb < 0) throw new EOFException("truncated record (no tombstone flag)");

        long seqNo = readUVarLong(in);
        byte[] value = readBlob(in);
        return new Record(key, value, tomb != 0, seqNo);
    }

    // ---- convenience (caly rekord <-> byte[]) ------------------------------

    public static byte[] serialize(Record r) {
        var bos = new ByteArrayOutputStream();
        try {
            writeRecord(bos, r);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream nie rzuca realnie
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
