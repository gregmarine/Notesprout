package com.notesprout.android.recognition.personal

/**
 * The prescribed sentences for the "teach Notesprout your handwriting" enrollment flow.
 * Chosen for coverage: every letter in both cases across the set (pangrams + names),
 * all ten digits, and the common punctuation the recognizer must learn in the user's
 * hand. Each sentence is short enough to write on one line, and the wording is kept
 * family-friendly (weird is fine; off-putting is not).
 */
object EnrollmentScript {

    val SENTENCES: List<String> = listOf(
        "The quick brown fox jumps over the lazy dog.",
        "Pack my box with five dozen juice jugs!",
        "How vexingly quick daft zebras jump?",
        "Sphinx of black quartz, judge my vow.",
        "The five boxing wizards jump quickly.",
        "Meeting at 6:30 tomorrow with Sarah & James.",
        "Call me at 555-0142 before 9 pm.",
        "Groceries: milk, eggs, bread, coffee (2 bags).",
        "The invoice total is $1,234.56 - due July 28th.",
        "Wednesday's quiz night starts at 7:45 pm.",
        "My email is hello@example.com, okay?",
        "Back up chapters 3, 7, and 12 today.",
        "Big July earthquakes confound zany experimental vow.",
        "Jack amazed a few girls by dropping the antique onyx vase!",
        "Just keep writing exactly how you always write.",
        "Where thought has a place to grow.",
    )
}
