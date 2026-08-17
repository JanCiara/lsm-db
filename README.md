# lsm-db

A miniature key-value store built on the **LSM-tree** architecture, written from scratch in Java 21.
No database libraries — the whole point of the project is to implement the engine myself.

## Build & test
```bash
./gradlew build      # compile + tests
./gradlew test
./gradlew jmh        # benchmarks -> build/results/jmh/results.txt
```

## API
```java
try (KVStore db = LsmStore.open(Path.of("data/"))) {
    db.put("user:1".getBytes(), "Janek".getBytes());
    Optional<byte[]> v = db.get("user:1".getBytes());
    db.delete("user:1".getBytes());
}

// custom settings
var opts = LsmStore.Options.defaults()   // 4 MiB memtable, compact at 4 tables, fsync
        .withFlushThresholdBytes(64 * 1024)
        .withDurability(Wal.Durability.OS_BUFFERED);

try (LsmStore db = LsmStore.open(Path.of("data/"), opts)) {
    db.put("k".getBytes(), "v".getBytes());
    db.flush();    // memtable -> new SSTable
    db.compact();  // all SSTables -> one
}
```

## How it works

```
put/delete ──► WAL (fsync) ──► memtable (sorted in-memory map)
                                   │  size threshold
                                   ▼
                              SSTable sst-000000.sst   (immutable)
                                      sst-000001.sst   (newer)
                                      ...                 │ table-count threshold
                                                          ▼
                                              merged into sst-000004.sst
```

A read walks from the freshest layer to the oldest — memtable, then SSTables newest-first — and the
first hit wins. If that hit is a tombstone, the key is deleted, even when an older table still holds
a value for it. After a successful flush the WAL is cleared; a crash between writing the table and
clearing the log only costs a repeated replay, never data loss.

## Compaction

Every write only appends — an overwrite leaves the old value behind, and `delete` **adds** a
tombstone. Without merging, files pile up forever and space taken by deleted keys never comes back.
Once the table count crosses the threshold, the engine merges all of them into one (a k-way merge
over cursors, holding only k records in memory), keeping a single version of each key and dropping
tombstones.

Dropping a tombstone is safe **only** when the merge includes the oldest table — otherwise it would
uncover the value it was hiding. Hence the explicit `dropTombstones` parameter.

The file swap survives a crash without a MANIFEST, thanks to two rules:

1. the merged table gets a **higher number** than its inputs, so it shadows them from the moment it
   appears on disk — a crash before cleanup leaves dead files, not bad data;
2. sources are deleted **oldest-first** — deleting top-down could take away a tombstone while leaving
   an older value of the same key underneath, resurrecting a deleted key (there is a test for this).

## On-disk record format
```
uvarint(keyLen) | key | byte(tombstone) | uvarint(seqNo) | uvarint(valLen) | value
```
Numbers are encoded as unsigned LEB128 varints (same as LevelDB / Protobuf).

## SSTable format
```
header:  "LSMT" | version(1B)
data:    record[entryCount]     — strictly increasing keys (unsigned), ~4 KiB blocks
index:   uvarint(blockCount) | { blob(first key of block) | uvarint(offset) }*
bloom:   uvarint(bits) | uvarint(k) | uvarint(words) | long[]
footer:  uvarint(entryCount) | uvarint(maxSeqNo) | blob(minKey) | blob(maxKey)
         | uvarint(index offset)
trailer: int32BE(footer length) | "LSMT"
```
The footer sits at the end because its metadata is only known after streaming through every record;
the fixed 8-byte trailer is what makes it findable, and the repeated magic catches a truncated file.
Writing goes through `.tmp` + fsync + atomic rename, so a reader never sees a half-written table.

## Point-lookup path

Each table filters the question through three sieves before touching the data on disk:

| sieve | cost | what it rejects |
|---|---|---|
| `minKey`/`maxKey` range from the footer | 2 comparisons | keys outside the table |
| Bloom filter (in memory) | 7 bit operations | ~99% of in-range misses |
| block index (in memory) | binary search | every block but one |

Only then is **a single block** (~4 KiB) read, rather than the whole file. Index and filter are
small, so `SSTable.open` loads them once and keeps them in memory. The filter uses 10 bits per key
and `k = 10·ln2 ≈ 7` hash functions computed by double hashing (`h_i = h1 + i·h2`,
Kirsch-Mitzenmacher) — one FNV-1a hash instead of seven independent ones. A "not here" answer is
certain, a "maybe" needs checking in the file; the filter produces no false negatives by
construction.

This matters most for **missed reads**: a key that does not exist would otherwise have to visit
every table in turn.

## Durability and crash behaviour

`Wal.Durability` decides what must happen to a record before `put` returns:

| mode | what it does | survives |
|---|---|---|
| `SYNC` (default) | flush + `fsync` | power loss, OS crash |
| `OS_BUFFERED` | flush to OS cache only | process death, but not machine death |

Three points where a crash can catch the engine, and what happens then:

1. **Mid-write to the WAL** — the log ends with an incomplete record. Replay yields every complete
   record, trims the tail back to the last healthy boundary, and the store opens normally. The lost
   write was never acknowledged to the caller, so no promise was broken.
2. **Between writing an SSTable and clearing the WAL** — replay returns records that are already on
   disk. They land in the memtable, i.e. the winning layer. No read sees an older value because of it.
3. **During post-merge cleanup** — see [Compaction](#compaction): the merged table has a higher
   number, and sources are deleted oldest-first.

What we **don't** catch: corruption in the middle of a file that happens to parse as a valid record.
That needs checksums (CRC per record) — deliberately out of scope. Implausible field lengths are
rejected though, so a flipped bit ends in a readable error instead of an `OutOfMemoryError`.

## Benchmarks (M5)

```bash
./gradlew jmh                               # everything, ~3 min
./gradlew jmh -PjmhIncludes=ReadBenchmark   # a single class
```

Windows 11, JDK 21, laptop. Absolute values depend on the hardware (especially the cost of `fsync`) —
what matters is the ratios. Average of 3 iterations of 2 s each, 1 fork.

**Writes** (`WriteBenchmark`, 100 B value):

| operation | `SYNC` | `OS_BUFFERED` |
|---|---|---|
| sequential `put` | 393 µs | 5.7 µs |
| random `put` | 429 µs | 7.1 µs |
| `delete` | 394 µs | 4.2 µs |

The prediction from the end of M4 held: **`fsync` costs ~65× the rest of the engine combined**.
Everything the memtable, the WAL and record encoding do disappears in the shadow of one system call.
Key layout (sequential vs random) barely matters — the difference fits inside the noise. `delete`
costs the same as `put`, because it really is a write.

**Reads** (`ReadBenchmark`, 2000 keys per table):

| operation | 1 table | 4 tables | 16 tables |
|---|---|---|---|
| hit in memtable | 0.30 µs | 0.33 µs | 0.31 µs |
| hit in newest table | 5.9 µs | 6.1 µs | 6.0 µs |
| hit in oldest table | 6.2 µs | 6.0 µs | 5.9 µs |
| miss | 0.33 µs | 0.39 µs | 0.54 µs |

Two things stand out. First, **hit cost does not grow with the number of tables** — the Bloom filter
rejects tables without the key, so a single block is read whether there are 1 or 16 tables. Second,
**a miss is cheaper than a hit** (~0.5 µs vs ~6 µs) and grows by roughly 14 ns per extra table — that
is pure bit work. Without the filter a miss would have to read one block from every table, so at 16
tables it would be on the order of 100 µs instead of 0.5 µs. That is exactly what M4 bought.

**Compaction** (`CompactionBenchmark`): 15–17 ms for 2, 4 and 8 tables of 2000 keys. Run-to-run
spread here is so wide (±60–80 ms) that differences between the variants are meaningless — this
measurement supports an order of magnitude, not a scaling curve.

### What the benchmark uncovered

The first run showed **85 µs** for an SSTable hit — 250× slower than the memtable, which reading a
4 KiB block does not explain. The cause: every `get` called `Files.newInputStream`, reopening the
file from scratch. The `open()` alone was ~99% of read time. After switching to one channel held
open for the table's lifetime and positional reads (`channel.read(buffer, position)`, which does not
move the channel position, so several cursors can read in parallel) it came down to **6 µs**.
Thirteen times cheaper, at the cost of `SSTable` now being `Closeable` and `LsmStore` having to close
its tables.

## Known limitations

Things deliberately left out of scope — not because they are unimportant, but because the project
ends at M5:

- **no checksums** — a bit flip in the middle of a file that still parses as a valid record goes
  unnoticed (LevelDB has CRC32 per block);
- **no concurrency** — every class assumes a single thread;
- **flush and compaction block the writer** — they run on the thread calling `put`, so every so often
  one write pays for rewriting the entire data set; real engines do this in the background against a
  frozen memtable;
- **the compaction policy is naive** — a table-count threshold and merging everything at once, which
  gives full space reclamation at the cost of write amplification (leveled/size-tiered merges only
  similar sizes);
- **no range scan** — only `get`/`put`/`delete`; an iterator would mean exposing the k-way merge that
  already exists inside `SSTable.compact`;
- **table list comes from a directory listing, with no MANIFEST** — good enough, because the file
  swap is crash-safe on its own (see Compaction), but it does not scale to many levels.

## References
- DDIA (Kleppmann), ch. 3 — Storage and Retrieval
- LevelDB / RocksDB — design docs
