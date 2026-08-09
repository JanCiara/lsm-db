# lsm-db

Mini-baza klucz-wartosc na architekturze **LSM-tree**, pisana od zera w Javie 21.
Bez bibliotek bazodanowych — sens projektu to zaimplementowac silnik samodzielnie.

> Status: **M4** — WAL + memtable + SSTable + scalanie + indeks blokowy i filtr Blooma.
> Roadmap: ~~M1 memtable+WAL~~ · ~~M2 SSTable~~ · ~~M3 compaction~~ · ~~M4 bloom+index~~ · M5 benchmarki.

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

// wlasny prog zrzutu memtable (domyslnie 4 MiB) i liczba tabel wyzwalajaca scalanie (domyslnie 4)
try (LsmStore db = LsmStore.open(Path.of("data/"), 64 * 1024, 4)) {
    db.put("k".getBytes(), "v".getBytes());
    db.flush();    // memtable -> nowa SSTable
    db.compact();  // wszystkie SSTable -> jedna
}
```

## Jak to dziala

```
put/delete ──► WAL (fsync) ──► memtable (posortowana mapa w pamieci)
                                   │  prog rozmiaru
                                   ▼
                              SSTable sst-000000.sst   (niemutowalna)
                                      sst-000001.sst   (nowsza)
                                      ...                 │ prog liczby tabel
                                                          ▼
                                              scalenie w sst-000004.sst
```

Odczyt schodzi od najswiezszej warstwy do najstarszej — memtable, potem SSTable od najnowszej —
i pierwsze trafienie wygrywa. Jesli trafiony rekord to tombstone, klucz jest usuniety, nawet gdy
starsza tabela ma dla niego wartosc. Po udanym zrzucie WAL jest zerowany; crash pomiedzy zapisem
tabeli a zerowaniem logu kosztuje tylko powtorzony replay, nigdy utrate danych.

## Compaction

Kazdy zapis tylko dopisuje — nadpisanie zostawia stara wartosc, a `delete` **dodaje** tombstone.
Bez scalania plikow przybywa w nieskonczonosc, a miejsce po skasowanych kluczach nigdy nie wraca.
Po przekroczeniu progu liczby tabel silnik scala je wszystkie w jedna (k-way merge po kursorach,
w pamieci tylko k rekordow), zostawiajac po jednej wersji kazdego klucza i wyrzucajac tombstone'y.

Odrzucenie tombstone'a jest bezpieczne **tylko** przy scalaniu obejmujacym najstarsza tabele —
inaczej odslonilby zakryta pod nim wartosc. Stad jawny parametr `dropTombstones`.

Podmiana plikow przezywa crash bez MANIFESTu, dzieki dwom regulom:

1. scalona tabela dostaje **numer wyzszy** niz wejsciowe, wiec przykrywa je od chwili pojawienia
   sie na dysku — crash przed sprzataniem zostawia martwe pliki, nie zle dane;
2. zrodla kasujemy **od najstarszego** — kasowanie od gory moglo by zabrac tombstone'a, zostawiajac
   pod nim starsza wartosc, czyli wskrzesic skasowany klucz (jest na to test).

## Format rekordu na dysku
```
uvarint(keyLen) | key | byte(tombstone) | uvarint(seqNo) | uvarint(valLen) | value
```
Liczby kodowane jako unsigned LEB128 varint (jak w LevelDB / Protobuf).

## Format SSTable
```
naglowek:    "LSMT" | wersja(1B)
dane:        record[entryCount]     — klucze scisle rosnace (unsigned), bloki po ~4 KiB
indeks:      uvarint(blockCount) | { blob(pierwszy klucz bloku) | uvarint(offset) }*
bloom:       uvarint(bity) | uvarint(k) | uvarint(slowa) | long[]
stopka:      uvarint(entryCount) | uvarint(maxSeqNo) | blob(minKey) | blob(maxKey)
             | uvarint(offset indeksu)
zakonczenie: int32BE(dlugosc stopki) | "LSMT"
```
Stopka jest na koncu, bo metadane sa znane dopiero po przejsciu wszystkich rekordow; stale
8 bajtow zakonczenia pozwala ja odnalezc, a powtorzony magic wykrywa uciety plik. Zapis idzie
przez `.tmp` + fsync + atomowy rename, wiec czytelnik nigdy nie widzi polowicznej tabeli.

## Sciezka odczytu punktowego

Kazda tabela odsiewa pytanie trzema sitami, zanim ruszy dysk z danymi:

| sito | koszt | co odrzuca |
|---|---|---|
| zakres `minKey`/`maxKey` ze stopki | 2 porownania | klucze spoza tabeli |
| filtr Blooma (w pamieci) | 7 operacji na bitach | ~99% chybien w zakresie |
| indeks blokowy (w pamieci) | wyszukiwanie binarne | wszystkie bloki poza jednym |

Dopiero potem czytany jest **jeden blok** (~4 KiB), a nie caly plik. Indeks i filtr sa male, wiec
`SSTable.open` wczytuje je raz i trzyma w pamieci. Filtr ma 10 bitow na klucz i `k = 10·ln2 ≈ 7`
funkcji mieszajacych, liczonych podwojnym haszowaniem (`h_i = h1 + i·h2`, Kirsch-Mitzenmacher) —
jeden hash FNV-1a zamiast siedmiu niezaleznych. Odpowiedz „nie ma" jest pewna, „moze byc" wymaga
sprawdzenia w pliku; falszywych negatywow filtr nie produkuje z konstrukcji.

Ma to znaczenie glownie dla **chybionych odczytow**: klucz, ktorego nie ma, musialby inaczej
odwiedzic kazda tabele po kolei.

## References
- DDIA (Kleppmann), rozdz. 3 — Storage and Retrieval
- LevelDB / RocksDB — design docs
