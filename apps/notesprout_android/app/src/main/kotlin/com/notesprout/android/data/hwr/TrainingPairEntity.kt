package com.notesprout.android.data.hwr

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One handwriting training pair: a line of ink plus its known-correct transcription.
 * The raw strokes are stored (not a rendered image) so training can re-rasterize with
 * augmentation and any future rasterizer version — see docs/handwriting-recognition.md
 * § "TrOCR engine".
 *
 * INVARIANT (leak hygiene): rows only ever originate from PLAINTEXT notebooks or from the
 * explicitly opt-in enrollment flow — `TrainingPairRepository.captureAllowed` gates every
 * capture. This DB lives unencrypted at `filesDir/hwr/training.db`; encrypting it with
 * SQLCipher is documented future work (BACKLOG), deliberately out of scope for Phase 2.
 */
@Entity(
    tableName = "training_pairs",
    indices = [Index(value = ["objectId"]), Index(value = ["confirmed", "createdAt"])],
)
data class TrainingPairEntity(
    @PrimaryKey val id: String,
    /** enrollment | line_correction | heading_conversion | heading_correction | lab */
    val source: String,
    /** Ground-truth text for the ink — no markdown prefixes. */
    val label: String,
    /**
     * What the engine originally read for this ink (before any human correction), or null
     * when unknown. Correction memory learns "wrong → right" substitutions from
     * (originalText, label) pairs; preserved when a correction upgrades [label].
     */
    val originalText: String?,
    /** kotlinx-serialized List<LiveStroke> (page coordinates). */
    val strokesJson: String,
    /** Page median line height at capture time, 0 when unknown (training normalizes per line anyway). */
    val refLineHeight: Float,
    val notebookId: String?,
    val pageId: String?,
    /** Source-object key (e.g. heading id) so a later correction upgrades the same pair. */
    val objectId: String?,
    /**
     * false = label is an engine's unverified output (e.g. heading just converted);
     * true = a human confirmed/corrected it. Only confirmed pairs feed the lexicon and fine-tuning.
     */
    val confirmed: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
