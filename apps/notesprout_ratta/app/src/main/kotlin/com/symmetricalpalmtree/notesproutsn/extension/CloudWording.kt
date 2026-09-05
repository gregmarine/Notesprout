package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The four words the Backup screen's Cloud line can end in, plus the one it falls back to when the
 * provider did not answer — handed in by the caller so that [CloudWording] itself holds no
 * resources and can be tested on the JVM.
 */
class CloudWords(
    /** Configured, no account: "not connected". */
    val notConnected: String,
    /** Connected, but the provider gave no label to print: "connected". */
    val connected: String,
    /** The extension was built without its credentials: "not set up". */
    val notConfigured: String,
    /** The provider is installed but did not answer: "unavailable". */
    val unavailable: String,
)

/**
 * The Cloud section's status line, as a **pure function** (arc 25 / V2) — the one piece of that
 * section worth testing, because it is the piece with four cases and a rule about which wins.
 *
 * The line is always `<providerName>: <something>`, and the order of the questions is the order they
 * matter in:
 *
 *  1. **not configured** — the extension APK was built without its client credentials, so no one can
 *     sign in on this build at all. It comes first because it makes every other answer moot, and
 *     because the person needs to know that tapping Connect is not going to work.
 *  2. **not connected** — configured, but no account. This is what Connect is for.
 *  3. **the account label** — connected, and the provider said what to call the account. This is the
 *     one branch that prints user content, and it prints it on the person's own screen only: the
 *     label is never logged, on either side of the seam.
 *  4. **connected** — connected, but with no label to print. A provider is allowed to have no
 *     display name for an account, and "connected" is more honest than an empty line.
 *
 * [unavailableLine] is the fifth case, which is not a [CloudStatus] at all: the provider is
 * installed but did not answer. It says so and leaves the button on **Connect** — a screen that
 * cannot ask does not get to claim the account is gone.
 *
 * [joiner] exists so the colon comes from a string resource in the app and from the default here in
 * a test; nothing else about the line varies.
 */
object CloudWording {

    /** The default join — `"<provider>: <detail>"`. The app passes its own from a string resource. */
    val DEFAULT_JOINER: (String, String) -> String = { provider, detail -> "$provider: $detail" }

    fun statusLine(
        status: CloudStatus,
        words: CloudWords,
        joiner: (String, String) -> String = DEFAULT_JOINER,
    ): String = joiner(status.providerName, detail(status, words))

    /** The provider is installed but did not answer — the [ExtensionCallException] case. */
    fun unavailableLine(
        providerName: String,
        words: CloudWords,
        joiner: (String, String) -> String = DEFAULT_JOINER,
    ): String = joiner(providerName, words.unavailable)

    /** What follows the provider's name. See the class doc for why the order is this one. */
    fun detail(status: CloudStatus, words: CloudWords): String = when {
        !status.configured -> words.notConfigured
        !status.connected -> words.notConnected
        status.accountLabel.isNotEmpty() -> status.accountLabel
        else -> words.connected
    }

    /**
     * Whether the section's one button should read **Disconnect** rather than **Connect**. Only a
     * live connection turns it over: an unavailable provider, an unconfigured build and a
     * disconnected account all leave it as Connect, because Connect is the only thing that can help
     * in any of those three.
     */
    fun showsDisconnect(status: CloudStatus?): Boolean = status != null && status.connected
}
