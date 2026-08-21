package com.symmetricalpalmtree.notesprout.extension;

import com.symmetricalpalmtree.notesprout.extension.LargeValue;

/**
 * A host-owned, encrypted key/value store scoped to the calling extension. The host mints one of
 * these per bind, bound to the extension's uid, and hands it in as a parameter of the calls that may
 * need it; it is revoked when the bind ends. Any exception (SecurityException, IllegalArgument /
 * IllegalState, RemoteException) means "store unavailable" — treat it as such.
 */
interface IExtensionStore {
    /** Value for [key], or null if absent. Throws IllegalStateException(STORE_VALUE_LARGE) if the stored
     *  value is above STORE_MAX_INLINE_BYTES — use getLarge. */
    byte[] get(String key);

    /** Insert or replace. key 1..512 chars, value <= STORE_MAX_INLINE_BYTES (512 KiB — larger values go
     *  through putLarge), <= 50 000 keys per extension. */
    void put(String key, in byte[] value);

    /** Remove [key] (no-op if absent). */
    void delete(String key);

    /** Keys starting with [prefix] ("" = all), ascending. */
    List<String> keys(String prefix);

    // ── arc 6 / S0 — APPENDED after keys(); the four methods above keep their transaction codes ──

    /** Insert or replace a value up to STORE_MAX_VALUE_BYTES (4 MiB) carried in an ashmem region the
     *  caller created (RenderedTemplate's handshake: create, write, setProtect(PROT_READ), hand over;
     *  the host copies in and closes ITS handle in onTransact's finally; the caller closes its own).
     *  Same key / key-count rules as put. */
    void putLarge(String key, in LargeValue value);

    /** The value for [key] of any size (null if absent) as a read-only region the host created; the
     *  caller maps, copies out exactly byteCount bytes, and closes it. */
    LargeValue getLarge(String key);
}
