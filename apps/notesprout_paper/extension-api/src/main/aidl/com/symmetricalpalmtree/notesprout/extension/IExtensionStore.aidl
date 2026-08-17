package com.symmetricalpalmtree.notesprout.extension;

/**
 * A host-owned, encrypted key/value store scoped to the calling extension. The host mints one of
 * these per bind, bound to the extension's uid, and hands it in as a parameter of the calls that may
 * need it; it is revoked when the bind ends. Any exception (SecurityException, IllegalArgument /
 * IllegalState, RemoteException) means "store unavailable" — treat it as such.
 */
interface IExtensionStore {
    /** Value for [key], or null if absent. */
    byte[] get(String key);

    /** Insert or replace. key 1..512 chars, value <= 256 KiB, <= 50 000 keys per extension. */
    void put(String key, in byte[] value);

    /** Remove [key] (no-op if absent). */
    void delete(String key);

    /** Keys starting with [prefix] ("" = all), ascending. */
    List<String> keys(String prefix);
}
