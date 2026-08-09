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
 * Ile kosztuje scalenie i jak skaluje sie z liczba tabel.
 *
 * <p>To pomiar write amplification przyjetej w M3 polityki: scalamy <b>wszystkie</b> tabele naraz,
 * wiec kazde scalenie przepisuje caly zbior danych. Benchmark pokazuje cene tej prostoty i daje
 * punkt odniesienia, gdyby kiedys wejsc w leveled albo size-tiered.
 *
 * <p>Mierzymy pojedyncze wywolanie ({@code SingleShotTime}), bo scalenie jest operacja rzadka
 * i droga — usrednianie milionow powtorzen nic by tu nie powiedzialo.
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
     * Swiezy sklep przed kazdym pomiarem — scalenie jest jednorazowe, drugie wywolanie na tym
     * samym stanie mierzyloby juz tylko no-op na jednej tabeli.
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
                // Klucze celowo sie powtarzaja miedzy tabelami — scalanie ma co odrzucac.
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
        return String.format("klucz:%012d", i).getBytes(StandardCharsets.UTF_8);
    }
}
