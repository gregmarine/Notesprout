package com.notesprout.android.recognition

/**
 * App-level singleton that holds the active HandwritingRecognizer.
 *
 * Initialized once in NotesproutApplication.onCreate().
 * All callers (DrawingActivity, future text box logic, etc.) use
 * HandwritingRecognizerProvider.instance to access recognition.
 */
object HandwritingRecognizerProvider {

    @Volatile
    private var _instance: HandwritingRecognizer? = null

    val instance: HandwritingRecognizer?
        get() = _instance

    /** Called only from NotesproutApplication. */
    internal fun init(recognizer: HandwritingRecognizer) {
        _instance = recognizer
    }

    /** Called from NotesproutApplication.onTerminate(). */
    internal fun shutdown() {
        _instance?.close()
        _instance = null
    }
}
