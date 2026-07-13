package com.notesprout.android

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.databinding.ActivityHwrSettingsBinding
import com.notesprout.android.recognition.HwrSettings
import com.notesprout.android.recognition.HandwritingRecognizerProvider
import com.notesprout.android.recognition.personal.TrainingPairRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Handwriting Recognition settings: choose the engine (Standard = ML Kit, Personal = TrOCR)
 * and manage the TrOCR model bundle (import via SAF with SHA-256 + smoke-decode verification,
 * switch between installed versions, delete).
 *
 * The Personal engine only routes while a model is installed — the Provider silently uses
 * ML Kit otherwise, so nothing here can strand the app without recognition.
 */
class HwrSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHwrSettingsBinding

    private val store get() = HandwritingRecognizerProvider.trOcrEngine?.modelStore

    private val exportTraining =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val result = com.notesprout.android.recognition.personal.TrainingBundleExporter
                    .export(applicationContext, uri)
                result.fold(
                    onSuccess = { n ->
                        Toast.makeText(this@HwrSettingsActivity, "Exported $n training samples.", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { showError("Export failed", it.message ?: "Unknown error") },
                )
            }
        }

    private val importModel =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            val s = store ?: return@registerForActivityResult
            @Suppress("DEPRECATION")
            val progress = ProgressDialog(this).apply {
                setMessage("Verifying and installing model…")
                setCancelable(false)
                show()
                window?.setElevation(0f)
                window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
            }
            lifecycleScope.launch {
                val result = s.installFromUri(uri)
                progress.dismiss()
                result.fold(
                    onSuccess = {
                        Toast.makeText(this@HwrSettingsActivity, "Model installed: ${it.name}", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = {
                        showError("Import failed", it.message ?: "Unknown error")
                    },
                )
                refresh()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHwrSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rowMlKit.setOnClickListener { selectEngine(HwrSettings.ENGINE_MLKIT) }
        binding.rowTrOcr.setOnClickListener { selectEngine(HwrSettings.ENGINE_TROCR) }
        binding.btnImportModel.setOnClickListener { importModel.launch(arrayOf("application/zip")) }
        binding.btnDeleteModel.setOnClickListener { confirmDelete() }
        binding.btnSwitchModel.setOnClickListener { showSwitchDialog() }
        binding.rowPersonalization.setOnClickListener {
            HwrSettings.setPersonalizationEnabled(this, !HwrSettings.personalizationEnabled(this))
            refresh()
        }
        binding.btnEnroll.setOnClickListener {
            startActivity(Intent(this, HwrEnrollmentActivity::class.java))
        }
        binding.btnClearTraining.setOnClickListener { confirmClearTraining() }
        binding.btnExportTraining.setOnClickListener {
            exportTraining.launch(
                com.notesprout.android.recognition.personal.TrainingBundleExporter.suggestedName()
            )
        }

        if (BuildConfig.DEBUG) {
            binding.btnOpenLab.isVisible = true
            binding.btnOpenLab.setOnClickListener {
                // HwrLabActivity lives in the debug source set — resolve by name.
                startActivity(Intent().setClassName(this, "com.notesprout.android.HwrLabActivity"))
            }
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from enrollment (or any child flow) must show fresh sample counts.
        refresh()
    }

    private fun selectEngine(engine: String) {
        if (engine == HwrSettings.ENGINE_TROCR && store?.activeModelDir() == null) {
            Toast.makeText(this, "Import a model first — Personal needs a model file.", Toast.LENGTH_LONG).show()
            return
        }
        HwrSettings.setEngine(this, engine)
        refresh()
    }

    private fun refresh() {
        val engine = HwrSettings.engine(this)
        binding.radioMlKit.isChecked = engine == HwrSettings.ENGINE_MLKIT
        binding.radioTrOcr.isChecked = engine == HwrSettings.ENGINE_TROCR

        lifecycleScope.launch {
            val s = store
            val active = withContext(Dispatchers.IO) { s?.activeManifest() }
            val versions = withContext(Dispatchers.IO) { s?.listVersions().orEmpty() }
            val sizeMb = withContext(Dispatchers.IO) {
                s?.activeModelDir()?.let { dir -> dirSize(dir) / (1 shl 20) }
            }

            binding.modelStatus.text = if (active == null) {
                "No model installed.\nImport a bundle (.zip) produced by the Notesprout HWR tools."
            } else {
                buildString {
                    append(active.name)
                    if (active.personalized) append(" · personalized")
                    append("\nVersion: ${active.versionId}")
                    sizeMb?.let { append(" · $it MB") }
                    append("\nCreated: ${DateFormat.getDateInstance().format(Date(active.createdAt))}")
                    if (versions.size > 1) append("\nInstalled versions: ${versions.size}")
                }
            }
            binding.btnDeleteModel.isVisible = active != null
            binding.btnSwitchModel.isVisible = versions.size > 1

            binding.checkPersonalization.isChecked = HwrSettings.personalizationEnabled(this@HwrSettingsActivity)
            val samples = TrainingPairRepository.confirmedCount(this@HwrSettingsActivity)
            binding.samplesStatus.text = "$samples handwriting samples collected"
            binding.btnExportTraining.isVisible = samples > 0
        }
    }

    private fun confirmClearTraining() {
        AlertDialog.Builder(this)
            .setTitle("Clear handwriting data?")
            .setMessage("Deletes all collected handwriting samples (corrections and teaching sentences). The Personal engine forgets what it learned; a personalized model already built from them is unaffected.")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    TrainingPairRepository.clearAll(this@HwrSettingsActivity)
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { d ->
                d.show()
                d.window?.setElevation(0f)
                d.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
            }
    }

    private fun confirmDelete() {
        val s = store ?: return
        val active = s.activeManifest() ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete model?")
            .setMessage("Delete \"${active.name}\" (${active.versionId})? Recognition will use Standard (ML Kit) until another model is imported.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { s.delete(active.versionId) }
                    if (store?.activeModelDir() == null) {
                        HwrSettings.setEngine(this@HwrSettingsActivity, HwrSettings.ENGINE_MLKIT)
                    }
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { d ->
                d.show()
                d.window?.setElevation(0f)
                d.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
            }
    }

    private fun showSwitchDialog() {
        val s = store ?: return
        lifecycleScope.launch {
            val versions = withContext(Dispatchers.IO) { s.listVersions() }
            if (versions.isEmpty()) return@launch
            val labels = versions.map { m ->
                val tag = if (m.personalized) "personalized" else "base"
                "${m.versionId} ($tag)"
            }.toTypedArray()
            AlertDialog.Builder(this@HwrSettingsActivity)
                .setTitle("Active model")
                .setItems(labels) { _, which ->
                    s.activate(versions[which].versionId)
                    refresh()
                }
                .create()
                .also { d ->
                    d.show()
                    d.window?.setElevation(0f)
                    d.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
                }
        }
    }

    private fun showError(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .create()
            .also { d ->
                d.show()
                d.window?.setElevation(0f)
                d.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
            }
    }

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
