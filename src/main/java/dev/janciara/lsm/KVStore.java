package dev.janciara.lsm;

import java.util.Optional;

/**
 * The public contract of the key-value engine.
 *
 * <p>Keys and values are raw bytes — the engine imposes no encoding. {@link LsmStore} is the
 * implementation; this interface exists so the rest of the project has a stable anchor point.
 */
public interface KVStore extends AutoCloseable {

    /** Stores (or overwrites) the value for a key. */
    void put(byte[] key, byte[] value);

    /** Returns the current value of a key, or empty if it does not exist / was deleted. */
    Optional<byte[]> get(byte[] key);

    /** Deletes a key (logically — internally this writes a tombstone). */
    void delete(byte[] key);

    /** Flush + release resources (WAL, open files). Throws no checked exceptions. */
    @Override
    void close();
}
