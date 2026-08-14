package com.notesprout.android.data.export

import com.notesprout.android.export.ExportDestination
import com.notesprout.android.export.ExportFormat
import com.notesprout.android.export.ExportSpec
import com.notesprout.android.export.SoilKeying
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A saved set of export choices, selectable from the top of the export screen.
 *
 * **A preset never holds a secret.** [usePdfPassword] and [soilKeying] record what the user
 * *chose*, not the password or passphrase behind it — selecting a preset that needs one re-opens
 * the prompt so the secret is typed fresh every time and is never written to disk. This is the same
 * rule the rest of the app follows for passphrases (see docs/encryption.md).
 *
 * Page scope is deliberately **not** captured: it belongs to how the export screen was opened
 * (a Page Index selection, the current notebook page) rather than to a reusable preference, and a
 * preset demanding "Selected (6)" is meaningless when nothing is selected.
 *
 * The text **Source** choice (notebook document vs page documents) is not captured either, for the
 * same reason — it only exists at all-pages scope, and only for a notebook that has a merged
 * notebook document; the screen defaults it to the notebook document whenever one exists.
 */
@Serializable
data class ExportPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val format: ExportFormat,
    val destination: ExportDestination,
    val includeTemplate: Boolean = true,
    val stickyEndnotes: Boolean = true,
    /** The user ticked "Protect PDF with a password" — the password itself is never stored. */
    val usePdfPassword: Boolean = false,
    /** For `.soil` of an encrypted notebook. `NEW` re-prompts for the passphrase on selection. */
    val soilKeying: SoilKeying = SoilKeying.KEEP,
    /** Drive folder path (names under "Notesprout Exports") for [ExportDestination.DRIVE]. */
    val drivePath: List<String> = emptyList(),
) {
    /** True when applying this preset must open a prompt to collect a secret it cannot store. */
    val needsSecret: Boolean
        get() = (format == ExportFormat.PDF && usePdfPassword) ||
            (format == ExportFormat.SOIL && soilKeying == SoilKeying.NEW)

    companion object {
        /** Capture the screen's current choices under [name]. Secrets in [spec] are discarded. */
        fun from(name: String, spec: ExportSpec): ExportPreset = ExportPreset(
            name = name,
            format = spec.format,
            destination = spec.destination,
            includeTemplate = spec.includeTemplate,
            stickyEndnotes = spec.stickyEndnotes,
            usePdfPassword = spec.pdfPassword != null,
            soilKeying = spec.soilKeying,
            drivePath = spec.drivePath,
        )
    }
}
