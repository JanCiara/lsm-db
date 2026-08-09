# lsm-db

Mini-baza klucz-wartosc na architekturze **LSM-tree**, pisana od zera w Javie 21.
Bez bibliotek bazodanowych — sens projektu to zaimplementowac silnik samodzielnie.

> Status: **M5** — kompletny silnik LSM z benchmarkami.
> Roadmap: ~~M1 memtable+WAL~~ · ~~M2 SSTable~~ · ~~M3 compaction~~ · ~~M4 bloom+index~~ · ~~M5 benchmarki~~.

## Build & test
```bash
./gradlew build      # kompilacja + testy
./gradlew test
./gradlew jmh        # benchmarki -> build/results/jmh/results.txt
```

## API
```java
try (KVStore db = LsmStore.open(Path.of("data/"))) {
    db.put("user:1".getBytes(), "Janek".getBytes());
    Optional<byte[]> v = db.get("user:1".getBytes());
    db.delete("user:1".getBytes());
}

// wlasne nastawy
var opts = LsmStore.Options.defaults()   // 4 MiB memtable, scalanie od 4 tabel, fsync
        .withFlushThresholdBytes(64 * 1024)
        .withDurability(Wal.Durability.OS_BUFFERED);

try (LsmStore db = LsmStore.open(Path.of("data/"), opts)) {
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

## Trwalosc i zachowanie po crashu

`Wal.Durability` decyduje, co ma sie stac z rekordem, zanim `put` wroci:

| tryb | co robi | przezywa |
|---|---|---|
| `SYNC` (domyslny) | flush + `fsync` | zanik pradu, crash OS-a |
| `OS_BUFFERED` | sam flush do cache OS-a | pad procesu, ale nie maszyny |

Trzy miejsca, w ktorych crash moze zastac silnik, i co sie wtedy dzieje:

1. **W polowie zapisu do WAL** — log konczy sie niekompletnym rekordem. Replay oddaje wszystkie
   kompletne rekordy, ucina ogon do ostatniej zdrowej granicy i baza wstaje normalnie. Utracony
   zapis nigdy nie zostal potwierdzony wywolujacemu, wiec zadna obietnica nie zostala zlamana.
2. **Miedzy zapisem SSTable a wyczyszczeniem WAL** — replay wraca rekordami, ktore juz sa na dysku.
   Trafiaja do memtable, czyli do warstwy wygrywajacej. Zaden odczyt nie zobaczy starszej wartosci.
3. **W trakcie sprzatania po scaleniu** — patrz [Compaction](#compaction): scalona tabela ma wyzszy
   numer, a zrodla kasowane sa od najstarszego.

Czego **nie** wykrywamy: uszkodzenia w srodku pliku, ktore przypadkiem parsuje sie na poprawny
rekord. Na to potrzebne sa sumy kontrolne (CRC per rekord) — swiadomie poza zakresem. Odsiewane sa
za to nierealne dlugosci pol, zeby przekrecony bit konczyl sie czytelnym bledem zamiast
`OutOfMemoryError`.

## Benchmarki (M5)

```bash
./gradlew jmh                            # calosc, ~3 min
./gradlew jmh -PjmhIncludes=ReadBenchmark   # jedna klasa
```

Windows 11, JDK 21, laptop. Wartosci bezwzgledne zaleza od sprzetu (szczegolnie koszt `fsync`) —
znaczenie maja proporcje. Srednia z 3 iteracji po 2 s, 1 fork.

**Zapis** (`WriteBenchmark`, wartosc 100 B):

| operacja | `SYNC` | `OS_BUFFERED` |
|---|---|---|
| `put` sekwencyjny | 393 µs | 5,7 µs |
| `put` losowy | 429 µs | 7,1 µs |
| `delete` | 394 µs | 4,2 µs |

Przewidywanie z konca M4 sie potwierdzilo: **`fsync` to ~65× caly reszta silnika razem wziety**.
Wszystko, co robi memtable, WAL i kodowanie rekordu, ginie w cieniu jednego wywolania systemowego.
Uklad kluczy (sekwencyjne vs losowe) prawie nie ma znaczenia — roznica miesci sie w rozrzucie.
`delete` kosztuje tyle samo co `put`, bo naprawde jest zapisem.

**Odczyt** (`ReadBenchmark`, 2000 kluczy na tabele):

| operacja | 1 tabela | 4 tabele | 16 tabel |
|---|---|---|---|
| trafienie w memtable | 0,30 µs | 0,33 µs | 0,31 µs |
| trafienie w najnowsza tabele | 5,9 µs | 6,1 µs | 6,0 µs |
| trafienie w najstarsza tabele | 6,2 µs | 6,0 µs | 5,9 µs |
| chybienie | 0,33 µs | 0,39 µs | 0,54 µs |

Dwie rzeczy widac od razu. Po pierwsze, **koszt trafienia nie rosnie z liczba tabel** — filtr Blooma
odsiewa tabele bez klucza, wiec czytany jest jeden blok niezaleznie od tego, czy tabel jest 1 czy 16.
Po drugie, **chybienie jest tansze niz trafienie** (~0,5 µs vs ~6 µs) i rosnie o jakies 14 ns na
kazda dodatkowa tabele — to czysta praca na bitach. Bez filtra chybienie musialoby przeczytac blok
z kazdej tabeli, czyli przy 16 tabelach byloby rzedu 100 µs zamiast 0,5 µs. To jest wlasnie to,
za co placilismy w M4.

**Scalanie** (`CompactionBenchmark`): 15–17 ms dla 2, 4 i 8 tabel po 2000 kluczy. Rozrzut miedzy
iteracjami jest tu tak duzy (±60–80 ms), ze roznice miedzy wariantami sa nieistotne — z tego pomiaru
wolno wyciagnac tylko rzad wielkosci, nie krzywa skalowania.

### Co benchmark wykryl

Pierwszy przebieg pokazal **85 µs** na trafienie w SSTable — 250× wolniej niz memtable, czego nie
tlumaczyl odczyt 4 KiB bloku. Przyczyna: kazdy `get` wolal `Files.newInputStream`, czyli otwieral
plik od nowa. Samo `open()` bylo ~99% czasu odczytu. Po zamianie na jeden kanal otwarty na cale
zycie tabeli i odczyty pozycyjne (`channel.read(buffer, position)`, ktore nie ruszaja pozycji
kanalu, wiec kilka kursorow moze czytac rownolegle) zostalo **6 µs**. Trzynastokrotnie taniej,
za cene tego, ze `SSTable` jest teraz `Closeable`, a `LsmStore` musi domykac tabele.

## Znane ograniczenia

Rzeczy swiadomie zostawione poza zakresem — nie dlatego, ze sa nieistotne, tylko dlatego, ze
projekt konczy sie na M5:

- **brak sum kontrolnych** — bit-flip w srodku pliku, ktory parsuje sie na poprawny rekord,
  przejdzie niezauwazony (LevelDB ma CRC32 na blok);
- **brak wielowatkowosci** — wszystkie klasy zakladaja jeden watek;
- **zrzut i scalanie blokuja piszacego** — dzieja sie w watku wolajacego `put`, wiec co jakis czas
  jeden zapis placi za przepisanie calego zbioru; prawdziwe silniki robia to w tle na zamrozonej
  memtable;
- **polityka scalania jest naiwna** — prog liczby tabel i scalanie wszystkiego naraz, co daje pelny
  odzysk miejsca kosztem write amplification (leveled/size-tiered scala tylko podobne rozmiary);
- **brak skanu zakresowego** — tylko `get`/`put`/`delete`; iterator wymagalby wystawienia k-way
  merge, ktory juz istnieje wewnatrz `SSTable.compact`;
- **lista tabel z `ls` katalogu, bez MANIFESTu** — wystarcza, bo podmiana plikow jest odporna na
  crash sama z siebie (patrz Compaction), ale nie skaluje sie na wiele poziomow.

## References
- DDIA (Kleppmann), rozdz. 3 — Storage and Retrieval
- LevelDB / RocksDB — design docs
