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
 * PRIVACY: capture is gated only by the personalization toggle
 * (`TrainingPairRepository.captureAllowed`). Encrypted notebooks DO contribute — under
 * encrypt-by-default the old "plaintext notebooks only" rule disabled the feature outright,
 * and the reason for that rule is gone: the store itself is SQLCipher-encrypted under the
 * global key (see [HwrTrainingDatabase], Phase 1b-ii), so pairs are protected at rest at the
 * same level as the `.soil` they came from. Nothing here leaves the device unless the user
 * explicitly exports a training bundle — that export IS plaintext, by necessity.
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
