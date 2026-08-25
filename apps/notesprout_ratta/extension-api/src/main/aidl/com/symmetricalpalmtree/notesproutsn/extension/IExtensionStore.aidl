// IExtensionStore.aidl — the host-owned encrypted key/value store an extension is lent for the
// life of one bind (arc 11 / J2). Not a capability point: a parameter of the calls that need it.
package com.symmetricalpalmtree.notesproutsn.extension;

import com.symmetricalpalmtree.notesproutsn.extension.LargeValue;

/**
 * A host-owned, encrypted key/value store scoped to the calling extension. The host mints one per
 * bind, bound to that extension's uid, and revokes it when the bind ends. Any exception
 * (SecurityException, IllegalArgumentException / IllegalStateException, RemoteException) means
 * "store unavailable" — the extension treats every one of them the same way.
 *
 * API_VERSION stays 1: the large pair is APPENDED after keys(), never reordered, so the four base
 * methods keep their transaction codes (the family's compatible-append recipe).
 */
interface IExtensionStore {
    /** Value for [key], or null if absent. Throws IllegalStateException(STORE_VALUE_LARGE) if the
     *  stored value is above STORE_MAX_INLINE_BYTES — use getLarge. */
    byte[] get(String key);

    /** Insert or replace. key 1..STORE_MAX_KEY_CHARS chars, value <= STORE_MAX_INLINE_BYTES
     *  (512 KiB — larger values go through putLarge), <= STORE_MAX_KEYS keys per extension. */
    void put(String key, in byte[] value);

    /** Remove [key] (no-op if absent). */
    void delete(String key);

    /** Keys starting with [prefix] ("" = all), ascending. */
    List<String> keys(String prefix);

    // ── arc 11 / J2 — APPENDED after keys(); the four methods above keep their transaction codes ──

    /** Insert or replace a value up to STORE_MAX_VALUE_BYTES (4 MiB) carried in an ashmem region
     *  the caller created (SharedBytes' handshake: create, write, setProtect(PROT_READ), hand over;
     *  the host copies in and closes ITS handle at once; the caller closes its own after the call).
     *  Same key / key-count rules as put. */
    void putLarge(String key, in LargeValue value);

    /** The value for [key] of any size (null if absent) as a read-only region the host created; the
     *  caller maps, copies out exactly byteCount bytes, and closes it (SharedBytes.readAndClose). */
    LargeValue getLarge(String key);
}
