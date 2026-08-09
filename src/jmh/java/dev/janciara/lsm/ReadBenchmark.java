package dev.janciara.lsm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Ile kosztuje {@code get} i jak ten koszt rosnie z liczba tabel na dysku.
 *
 * <p>Interesuja nas trzy rozne sytuacje, bo w LSM maja zupelnie inna charakterystyke:
 * <ul>
 *   <li><b>trafienie w memtable</b> — czysta pamiec, punkt odniesienia;</li>
 *   <li><b>trafienie w SSTable</b> — filtr Blooma przepuszcza, indeks wskazuje blok, czytamy go;</li>
 *   <li><b>chybienie</b> — musi odpytac <i>kazda</i> tabele. To wlasnie tu pracuje filtr Blooma
 *       i tu widac, po co byl M4.</li>
 * </ul>
 *
 * <p>Prog scalania jest podniesiony, zeby {@code tables} faktycznie oznaczalo liczbe tabel —
 * inaczej silnik zescalilby je w tle do jednej i benchmark mierzylby co innego, niz deklaruje.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ReadBenchmark {

    private static final int KEYS_PER_TABLE = 2_000;

    @Param({"1", "4", "16"})
    public int tables;

    private Path dir;
    private LsmStore store;
    private long counter;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("lsm-read-bench");
        store = LsmStore.open(dir, LsmStore.Options.defaults()
                .withDurability(Wal.Durability.OS_BUFFERED)   // mierzymy odczyt, nie fsync
                .withCompactionTrigger(Integer.MAX_VALUE));   // zadnego scalania w tle

        byte[] value = "x".repeat(100).getBytes(StandardCharsets.UTF_8);
        for (int t = 0; t < tables; t++) {
            for (int i = 0; i < KEYS_PER_TABLE; i++) {
                store.put(key(t * KEYS_PER_TABLE + i), value);
            }
            store.flush(); // kazda partia laduje w osobnej tabeli
        }
        store.put(key(-1), value); // jeden klucz zostaje w memtable
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        store.close();
        WriteBenchmark.deleteRecursively(dir);
    }

    /** Punkt odniesienia: klucz lezy w pamieci, dysk w ogole nie pracuje. */
    @Benchmark
    public void getFromMemtable(Blackhole bh) {
        bh.consume(store.get(key(-1)));
    }

    /** Trafienie w najstarsza tabele — najgorszy przypadek, bo przeszukujemy od najnowszej. */
    @Benchmark
    public void getFromOldestTable(Blackhole bh) {
        bh.consume(store.get(key(counter++ % KEYS_PER_TABLE)));
    }

    /** Trafienie w najnowsza tabele — pierwsza sprawdzana. */
    @Benchmark
    public void getFromNewestTable(Blackhole bh) {
        long base = (long) (tables - 1) * KEYS_PER_TABLE;
        bh.consume(store.get(key(base + counter++ % KEYS_PER_TABLE)));
    }

    /** Chybienie: klucz w zakresie, ale nieistniejacy — kazda tabela musi powiedziec „nie ma". */
    @Benchmark
    public void getMissing(Blackhole bh) {
        bh.consume(store.get(missingKey(counter++)));
    }

    private static byte[] key(long i) {
        return String.format("klucz:%012d", i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] missingKey(long i) {
        return String.format("klucz:%012d-brak", i).getBytes(StandardCharsets.UTF_8);
    }
}
