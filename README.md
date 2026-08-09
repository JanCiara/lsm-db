# lsm-db

Mini-baza klucz-wartosc na architekturze **LSM-tree**, pisana od zera w Javie 21.
Bez bibliotek bazodanowych — sens projektu to zaimplementowac silnik samodzielnie.

> Status: **M2** — WAL + memtable + zrzut do niemutowalnych SSTable na dysku.
> Roadmap: ~~M1 memtable+WAL~~ · ~~M2 SSTable~~ · M3 compaction · M4 bloom+index · M5 benchmarki.

## Build & test
```bash
./gradlew build      # kompilacja + testy
./gradlew test
```

## API
```java
try (KVStore db = LsmStore.open(Path.of("data/"))) {
    db.put("user:1".getBytes(), "Janek".getBytes());
    Optional<byte[]> v = db.get("user:1".getBytes());
    db.delete("user:1".getBytes());
}

// wlasny prog zrzutu memtable (domyslnie 4 MiB) + reczny zrzut
try (LsmStore db = LsmStore.open(Path.of("data/"), 64 * 1024)) {
    db.put("k".getBytes(), "v".getBytes());
    db.flush();
}
```

## Jak to dziala

```
put/delete ──► WAL (fsync) ──► memtable (posortowana mapa w pamieci)
                                   │  prog rozmiaru
                                   ▼
                              SSTable sst-000000.sst   (niemutowalna)
                                      sst-000001.sst   (nowsza)
```

Odczyt schodzi od najswiezszej warstwy do najstarszej — memtable, potem SSTable od najnowszej —
i pierwsze trafienie wygrywa. Jesli trafiony rekord to tombstone, klucz jest usuniety, nawet gdy
starsza tabela ma dla niego wartosc. Po udanym zrzucie WAL jest zerowany; crash pomiedzy zapisem
tabeli a zerowaniem logu kosztuje tylko powtorzony replay, nigdy utrate danych.

## Format rekordu na dysku
```
uvarint(keyLen) | key | byte(tombstone) | uvarint(seqNo) | uvarint(valLen) | value
```
Liczby kodowane jako unsigned LEB128 varint (jak w LevelDB / Protobuf).

## Format SSTable
```
naglowek:    "LSMT" | wersja(1B)
dane:        record[entryCount]     — klucze scisle rosnace (unsigned)
stopka:      uvarint(entryCount) | uvarint(maxSeqNo) | blob(minKey) | blob(maxKey)
zakonczenie: int32BE(dlugosc stopki) | "LSMT"
```
Stopka jest na koncu, bo metadane sa znane dopiero po przejsciu wszystkich rekordow; stale
8 bajtow zakonczenia pozwala ja odnalezc, a powtorzony magic wykrywa uciety plik. Zapis idzie
przez `.tmp` + fsync + atomowy rename, wiec czytelnik nigdy nie widzi polowicznej tabeli.

Wyszukiwanie w M2 jest liniowe; odsiewaja tylko `minKey`/`maxKey` ze stopki i wczesne przerwanie
skanu po minieciu klucza. Indeks blokowy i filtr Blooma dochodza w M4.

## References
- DDIA (Kleppmann), rozdz. 3 — Storage and Retrieval
- LevelDB / RocksDB — design docs
