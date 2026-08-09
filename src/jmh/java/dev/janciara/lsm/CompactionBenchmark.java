package dev.janciara.lsm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What a merge costs, and how it scales with the number of tables.
 *
 * <p>This measures the write amplification of the policy adopted in M3: we merge <b>all</b> tables
 * at once, so every merge rewrites the entire data set. The benchmark shows the price of that
 * simplicity and gives a baseline should leveled or size-tiered compaction ever be worth trying.
 *
 * <p>We measure a single invocation ({@code SingleShotTime}), because a merge is a rare and
 * expensive operation — averaging millions of repetitions would say nothing useful here.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
public class CompactionBenchmark {

    private static final int KEYS_PER_TABLE = 2_000;

    @Param({"2", "4", "8"})
    public int tables;

    private Path dir;
    private LsmStore store;

    /**
     * A fresh store before every measurement — a merge is one-shot, and a second call on the same
     * state would only measure a no-op on a single table.
     */
    @Setup(Level.Invocation)
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("lsm-compact-bench");
        store = LsmStore.open(dir, LsmStore.Options.defaults()
                .withDurability(Wal.Durability.OS_BUFFERED)
                .withCompactionTrigger(Integer.MAX_VALUE));

        byte[] value = "x".repeat(100).getBytes(StandardCharsets.UTF_8);
        for (int t = 0; t < tables; t++) {
            for (int i = 0; i < KEYS_PER_TABLE; i++) {
                // Keys repeat across tables on purpose — the merge has something to discard.
                store.put(key(i), value);
            }
            store.flush();
        }
    }

    @TearDown(Level.Invocation)
    public void tearDown() throws IOException {
        store.close();
        WriteBenchmark.deleteRecursively(dir);
    }

    @Benchmark
    public int compactAllTables() {
        store.compact();
        return store.sstableCount();
    }

    private static byte[] key(long i) {
        return String.format("key:%012d", i).getBytes(StandardCharsets.UTF_8);
    }
}
