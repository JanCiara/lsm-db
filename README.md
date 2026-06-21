# lsm-db

Mini-baza klucz-wartosc na architekturze **LSM-tree**, pisana od zera w Javie 21.
Bez bibliotek bazodanowych — sens projektu to zaimplementowac silnik samodzielnie.

> Status: **M0** — szkielet, publiczne API i format rekordu z serializacja varint.
> Roadmap: M1 memtable+WAL · M2 SSTable · M3 compaction · M4 bloom+index · M5 benchmarki.

## Build & test
```bash
./gradlew build      # kompilacja + testy
./gradlew test
```

## API (docelowo)
```java
try (KVStore db = LsmStore.open(Path.of("data/"))) {
    db.put("user:1".getBytes(), "Janek".getBytes());
    Optional<byte[]> v = db.get("user:1".getBytes());
    db.delete("user:1".getBytes());
}
```

## Format rekordu na dysku
```
uvarint(keyLen) | key | byte(tombstone) | uvarint(seqNo) | uvarint(valLen) | value
```
Liczby kodowane jako unsigned LEB128 varint (jak w LevelDB / Protobuf).

## References
- DDIA (Kleppmann), rozdz. 3 — Storage and Retrieval
- LevelDB / RocksDB — design docs
