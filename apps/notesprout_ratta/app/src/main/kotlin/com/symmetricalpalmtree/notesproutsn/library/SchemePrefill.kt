package com.symmetricalpalmtree.notesproutsn.library

import android.util.Log

/**
 * The name a folder's naming scheme suggests for the next notebook created in it (arc 5, shared by
 * arc 6 / K3). One recipe, two callers — the library's +Notebook and the link picker's New
 * notebook — so a scheme can never mean one thing in the library and another in the picker.
 *
 * It owns only the *rules*; the index reads stay with the caller, which is what keeps this pure
 * enough to JVM-test and what lets the picker fetch siblings from its own browse folder.
 *
 * Two guards are the reason this is not a one-liner:
 *  - **The siblings are fetched lazily.** `{n}` is the only token that asks what is already in the
 *    folder, so a scheme without a counter must never pay for that read ([siblings] is invoked only
 *    when the parsed scheme holds one).
 *  - **A name the library would refuse is not a prefill.** An expansion that fails [NameRules] — or
 *    that outgrew [SchemeEngine.MAX_SCHEME_CHARS] because the counter passed its declared width —
 *    falls back to the caller's own default rather than being handed to a screen that will reject
 *    it. Naming never blocks what the user asked for.
 */
object SchemePrefill {

    private const val TAG = "SchemePrefill"

    /**
     * The prefill [scheme] yields at [now], or **null whenever the caller's own default should
     * stand**: no scheme at all, a scheme that does not parse, or an expansion the library would
     * refuse. Never throws — a naming scheme is not worth a crash, and every caller runs this in a
     * `lifecycleScope` that has no handler.
     */
    suspend fun expand(scheme: String?, now: Long, siblings: suspend () -> List<String>): String? = try {
        if (scheme == null) null else {
            val parts = SchemeEngine.parse(scheme)
            val names = if (SchemeEngine.hasCounter(parts)) siblings() else emptyList()
            val expanded = SchemeEngine.expand(parts, now, names)
            if (NameRules.isValid(expanded) && expanded.length <= SchemeEngine.MAX_SCHEME_CHARS) {
                expanded
            } else {
                Log.w(TAG, "scheme expanded to an unusable name — using the default")
                null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "scheme expansion failed — using the default", e)
        null
    }
}
