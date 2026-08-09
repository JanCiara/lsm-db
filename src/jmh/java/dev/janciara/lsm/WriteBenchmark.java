package dev.janciara.lsm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

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

/**
 * What a single {@code put} costs, and where that cost comes from.
 *
 * <p>The main M5 question: does an fsync per record really outweigh the whole rest of the engine?
 * That is why the same write is measured in both durability modes — {@code SYNC} (record on the
 * platter) and {@code OS_BUFFERED} (record in the OS cache).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class WriteBenchmark {

    @Param({"SYNC", "OS_BUFFERED"})
    public Wal.Durability durability;

    private Path dir;
    private LsmStore store;
    private byte[] value;
    private long counter;

    @Setup(Level.Iteration)
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("lsm-write-bench");
        store = LsmStore.open(dir, LsmStore.Options.defaults().withDurability(durability));
        value = "x".repeat(100).getBytes(StandardCharsets.UTF_8);
        counter = 0;
    }

    @TearDown(Level.Iteration)
    public void tearDown() throws IOException {
        store.close();
        deleteRecursively(dir);
    }

    /** Ascending keys — the best case for the memtable (inserting at the end of the tree). */
    @Benchmark
    public void sequentialPut() {
        store.put(key(counter++), value);
    }

    /** Scattered keys — the memtable actually has to find a place in the tree. */
    @Benchmark
    public void randomPut() {
        store.put(key(scramble(counter++)), value);
    }

    /** A deletion is an ordinary write — we measure whether it really costs the same as a put. */
    @Benchmark
    public void delete() {
        store.delete(key(counter++));
    }

    private static byte[] key(long i) {
        return String.format("key:%012d", i).getBytes(StandardCharsets.UTF_8);
    }

    /** A cheap bit mixer — scatters consecutive numbers across the whole key space. */
    private static long scramble(long i) {
        long z = i * 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        return Math.abs((z ^ (z >>> 27)) % 100_000_000L);
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
