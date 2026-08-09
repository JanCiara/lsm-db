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
 * What a {@code get} costs, and how that cost grows with the number of tables on disk.
 *
 * <p>Three different situations are interesting, because in an LSM they behave nothing alike:
 * <ul>
 *   <li><b>a hit in the memtable</b> — pure memory, the baseline;</li>
 *   <li><b>a hit in an SSTable</b> — the Bloom filter lets it through, the index points at a block,
 *       and we read that block;</li>
 *   <li><b>a miss</b> — has to ask <i>every</i> table. This is where the Bloom filter earns its
 *       keep, and where M4 shows up in the numbers.</li>
 * </ul>
 *
 * <p>The compaction threshold is raised so that {@code tables} really does mean the number of
 * tables — otherwise the engine would merge them down to one behind our back and the benchmark
 * would measure something other than what it claims.
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
                .withDurability(Wal.Durability.OS_BUFFERED)   // we measure reads, not fsync
                .withCompactionTrigger(Integer.MAX_VALUE));   // no merging behind our back

        byte[] value = "x".repeat(100).getBytes(StandardCharsets.UTF_8);
        for (int t = 0; t < tables; t++) {
            for (int i = 0; i < KEYS_PER_TABLE; i++) {
                store.put(key(t * KEYS_PER_TABLE + i), value);
            }
            store.flush(); // each batch lands in its own table
        }
        store.put(key(-1), value); // one key stays in the memtable
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        store.close();
        WriteBenchmark.deleteRecursively(dir);
    }

    /** The baseline: the key sits in memory, the disk does no work at all. */
    @Benchmark
    public void getFromMemtable(Blackhole bh) {
        bh.consume(store.get(key(-1)));
    }

    /** A hit in the oldest table — the worst case, since the search starts from the newest. */
    @Benchmark
    public void getFromOldestTable(Blackhole bh) {
        bh.consume(store.get(key(counter++ % KEYS_PER_TABLE)));
    }

    /** A hit in the newest table — the first one checked. */
    @Benchmark
    public void getFromNewestTable(Blackhole bh) {
        long base = (long) (tables - 1) * KEYS_PER_TABLE;
        bh.consume(store.get(key(base + counter++ % KEYS_PER_TABLE)));
    }

    /** A miss: a key inside the range but nonexistent — every table has to say "not here". */
    @Benchmark
    public void getMissing(Blackhole bh) {
        bh.consume(store.get(missingKey(counter++)));
    }

    private static byte[] key(long i) {
        return String.format("key:%012d", i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] missingKey(long i) {
        return String.format("key:%012d-missing", i).getBytes(StandardCharsets.UTF_8);
    }
}
