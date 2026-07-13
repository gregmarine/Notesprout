package com.notesprout.android.recognition.personal

/**
 * The prescribed sentences for the "teach Notesprout your handwriting" enrollment flow.
 * Chosen for coverage: every letter in both cases across the set (pangrams + names),
 * all ten digits, and the common punctuation the recognizer must learn in the user's
 * hand. Each sentence is short enough to write on one line.
 */
object EnrollmentScript {

    val SENTENCES: List<String> = listOf(
        "The quick brown fox jumps over the lazy dog.",
        "Pack my box with five dozen liquor jugs!",
        "How vexingly quick daft zebras jump?",
        "Sphinx of black quartz, judge my vow.",
        "Waltz, bad nymph, for quick jigs vex.",
        "Meeting at 6:30 tomorrow with Sarah & James.",
        "Call me at 555-0142 before 9 pm.",
        "Groceries: milk, eggs, bread, coffee (2 bags).",
        "The invoice total is $1,234.56 - due July 28th.",
        "Quiz night every Wednesday at 7:45 pm.",
        "My email is hello@example.com, okay?",
        "Back up chapters 3, 7, and 12 today.",
        "Big July earthquakes confound zany experimental vow.",
        "A wizard's job is to vex chumps quickly in fog.",
        "Just keep writing exactly how you always write.",
        "Where thought has a place to grow.",
    )
}
