package com.symmetricalpalmtree.notesproutsn.extension

/**
 * What `IHandwritingRecognizer.status()` returns. Plain `Int` constants — AIDL carries `int`
 * natively, so no parcelable and no enum marshalling. The host treats any other value as
 * [UNAVAILABLE].
 */
object RecognizerStatus {
    /** Model on device, engine constructed; `recognize*` will run. */
    const val READY: Int = 0

    /** Model not on device; call `prepare()`. */
    const val NEEDS_DOWNLOAD: Int = 1

    /** `prepare()` started and the download is in flight; poll `status()`. */
    const val DOWNLOADING: Int = 2

    /** The engine cannot run here (identifier unknown, download failed permanently, …). */
    const val UNAVAILABLE: Int = 3
}
