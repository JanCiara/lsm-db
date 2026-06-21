package dev.janciara.lsm;

import java.util.Optional;

/**
 * Publiczny kontrakt silnika klucz-wartosc.
 *
 * <p>Klucze i wartosci to surowe bajty — silnik nie narzuca kodowania.
 * Implementacja (LsmStore) bedzie dodana w M1+. W M0 definiujemy tylko ksztalt API,
 * zeby reszta projektu miala stabilny punkt zaczepienia.
 */
public interface KVStore extends AutoCloseable {

    /** Zapisuje (lub nadpisuje) wartosc dla klucza. */
    void put(byte[] key, byte[] value);

    /** Zwraca aktualna wartosc klucza, lub empty jesli nie istnieje / zostal usuniety. */
    Optional<byte[]> get(byte[] key);

    /** Usuwa klucz (logicznie — wewnetrznie zapisze tombstone). */
    void delete(byte[] key);

    /** Flush + zamkniecie zasobow (WAL, otwarte pliki). Nie rzuca checked exceptions. */
    @Override
    void close();
}
