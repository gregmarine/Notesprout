package com.symmetricalpalmtree.notesproutsn.templates

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.template.BuiltInTemplates
import com.symmetricalpalmtree.notesproutsn.data.template.DensityMode
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateGeometry
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateSpec
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityTemplateOptionsBinding
import com.symmetricalpalmtree.notesproutsn.databinding.ViewOptionStepperBinding
import com.symmetricalpalmtree.notesproutsn.library.FolderPickerActivity
import com.symmetricalpalmtree.notesproutsn.library.NameDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Template options** (arc 13 / G2) — a generator's recipe, adjustable, beside the page it draws.
 *
 * The screen owns one [TemplateSpec] and nothing else. Every control pushes a new spec, the
 * preview redraws from it, and the three exits differ only in what they do with it: **Cancel**
 * throws it away, **Use once** hands it to whatever is waiting for a pick and stores nothing, and
 * **Save as template…** mints a static card — a *baked* variant, so changing its margins later
 * means coming back here and saving another.
 *
 * *Use once* is `GONE` with no pending pick (opened from the library there is nothing to apply it
 * to) rather than disabled: a greyed control is invisible on e-ink and reads as broken.
 *
 * The preview is a **true miniature** — the same render the page will get, at a smaller effective
 * dpi ([BuiltInTemplates.miniature]) — so what is on the glass is the paper, not an impression of
 * it. It is debounced: a held stepper fires every 110 ms and an EPD panel cannot draw that fast,
 * so the render waits for the hand to settle.
 */
class TemplateOptionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTemplateOptionsBinding
    private val repo by lazy { IndexRepository() }

    private var spec = TemplateSpec.stock(TemplateKind.LINED)

    /** The ⇄ latch: one axis's change mirrors the other's **physical spacing**, so cells stay square. */
    private var square = false

    /** The page the preview and every count are measured against. */
    private var pageWidthPx = 0
    private var pageHeightPx = 0

    private var startFolderId: String? = null
    private var pendingPick = false

    /** The destination chosen by the picker, waiting for the name dialog to produce a name. */
    private var saveFolderId: String? = null
    private var saving = false

    private var previewW = 0
    private var previewH = 0
    private var previewScale = 0f
    private var previewJob: Job? = null
    private var previewMeasured = false

    private lateinit var rows: OptionStepper
    private lateinit var cols: OptionStepper
    private lateinit var insetTop: OptionStepper
    private lateinit var insetBottom: OptionStepper
    private lateinit var insetLeft: OptionStepper
    private lateinit var insetRight: OptionStepper
    private lateinit var thickness: OptionStepper
    private lateinit var dot: OptionStepper
    private lateinit var shade: OptionStepper

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        saveFolderId = FolderPickerActivity.pickedFolderId(result.data)
        showNameDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        binding = ActivityTemplateOptionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        val kind = TemplateKind.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_KIND) }
            ?: TemplateKind.LINED
        // A spec may arrive (a variant being re-opened, a pick being adjusted); otherwise the
        // generator starts at its factory paper, which is what "Lined" has always meant.
        spec = TemplateSpec.decode(intent.getByteArrayExtra(EXTRA_SPEC))?.copy(kind = kind)
            ?: TemplateSpec.stock(kind)
        startFolderId = intent.getStringExtra(EXTRA_START_FOLDER)
        pendingPick = intent.getBooleanExtra(EXTRA_PENDING_PICK, false)

        val metrics = resources.displayMetrics
        pageWidthPx = intent.getIntExtra(EXTRA_PAGE_WIDTH, 0)
            .takeIf { it > 0 } ?: minOf(metrics.widthPixels, metrics.heightPixels)
        pageHeightPx = intent.getIntExtra(EXTRA_PAGE_HEIGHT, 0)
            .takeIf { it > 0 } ?: maxOf(metrics.widthPixels, metrics.heightPixels)

        binding.title.text = getString(titleFor(kind))
        binding.btnUseOnce.visibility = if (pendingPick) View.VISIBLE else View.GONE
        TooltipCompat.setTooltipText(binding.btnClose, binding.btnClose.contentDescription)

        buildSteppers()
        wireChrome()
        renderControls()

        binding.previewFrame.viewTreeObserver.addOnGlobalLayoutListener {
            if (previewMeasured) return@addOnGlobalLayoutListener
            measurePreview()
        }
    }

    override fun onDestroy() {
        if (IndexGuard.bounced(this)) { super.onDestroy(); return }
        listOf(rows, cols, insetTop, insetBottom, insetLeft, insetRight, thickness, dot, shade)
            .forEach { it.release() }
        super.onDestroy()
    }

    // ── Controls ─────────────────────────────────────────────────────────────

    private fun buildSteppers() {
        rows = densityStepper(binding.stepRows, vertical = true)
        cols = densityStepper(binding.stepCols, vertical = false)
        insetTop = insetStepper(binding.stepTop) { s, v -> s.copy(topMm = v) }
        insetBottom = insetStepper(binding.stepBottom) { s, v -> s.copy(bottomMm = v) }
        insetLeft = insetStepper(binding.stepLeft) { s, v -> s.copy(leftMm = v) }
        insetRight = insetStepper(binding.stepRight) { s, v -> s.copy(rightMm = v) }

        thickness = OptionStepper(
            binding.stepThickness,
            TemplateSpec.MIN_THICKNESS_MM, TemplateSpec.MAX_THICKNESS_MM, THICKNESS_STEP_MM,
            { getString(R.string.options_value_mm2, it) },
        ) { v -> update(spec.copy(thicknessMm = v)) }

        dot = OptionStepper(
            binding.stepDot,
            TemplateSpec.MIN_DOT_MM, TemplateSpec.MAX_DOT_MM, DOT_STEP_MM,
            { getString(R.string.options_value_mm2, it) },
        ) { v -> update(spec.copy(dotMm = v)) }

        shade = OptionStepper(
            binding.stepShade,
            TemplateSpec.SHADE_MIN.toFloat(), TemplateSpec.SHADE_BLACK.toFloat(), 1f,
            { getString(R.string.options_value_shade, it.toInt(), TemplateSpec.SHADE_BLACK) },
        ) { v -> update(spec.copy(shade = v.toInt())) }

        rows.label(getString(if (spec.kind == TemplateKind.LINED) R.string.options_lines else R.string.options_rows))
        cols.label(getString(R.string.options_columns))
        insetTop.label(getString(R.string.options_inset_top))
        insetBottom.label(getString(R.string.options_inset_bottom))
        insetLeft.label(getString(R.string.options_inset_left))
        insetRight.label(getString(R.string.options_inset_right))
        thickness.label(getString(R.string.options_thickness))
        dot.label(getString(R.string.options_dot))
        shade.label(getString(R.string.options_shade))

        cols.extra(getString(R.string.options_square)) { toggleSquare() }

        // Lined has one axis and no dots; the controls that would mean nothing are not there.
        cols.visible(spec.kind != TemplateKind.LINED)
        dot.visible(spec.kind == TemplateKind.DOTTED)
    }

    /**
     * A density axis: the same row means millimetres or a count depending on the mode, because it
     * is the same axis said two ways. Its bounds and step change with the mode, so it is rebuilt
     * on a toggle rather than reconfigured.
     */
    private fun densityStepper(row: ViewOptionStepperBinding, vertical: Boolean): OptionStepper {
        val counting = spec.rows.mode == DensityMode.COUNT
        return if (counting) OptionStepper(
            row,
            TemplateSpec.MIN_COUNT.toFloat(), TemplateSpec.MAX_COUNT.toFloat(), 1f,
            { it.toInt().toString() },
        ) { v -> onAxisChanged(vertical, count = v.toInt(), spacingMm = null) }
        else OptionStepper(
            row,
            TemplateSpec.MIN_SPACING_MM, TemplateSpec.MAX_SPACING_MM, SPACING_STEP_MM,
            { getString(R.string.options_value_mm1, it) },
        ) { v -> onAxisChanged(vertical, count = null, spacingMm = v) }
    }

    private fun insetStepper(
        row: ViewOptionStepperBinding,
        apply: (TemplateSpec, Float) -> TemplateSpec,
    ): OptionStepper = OptionStepper(
        row, 0f, TemplateSpec.MAX_INSET_MM, INSET_STEP_MM,
        { getString(R.string.options_value_mm1, it) },
    ) { v -> update(apply(spec, v)) }

    private fun wireChrome() = with(binding) {
        btnClose.setOnClickListener { finish() }
        btnCancel.setOnClickListener { finish() }
        btnModeSpacing.setOnClickListener { setMode(DensityMode.SPACING) }
        btnModeCount.setOnClickListener { setMode(DensityMode.COUNT) }
        btnCopyAll.setOnClickListener {
            update(spec.copy(bottomMm = spec.topMm, leftMm = spec.topMm, rightMm = spec.topMm))
        }
        btnCopySides.setOnClickListener { update(spec.copy(rightMm = spec.leftMm)) }
        btnCopyEnds.setOnClickListener { update(spec.copy(bottomMm = spec.topMm)) }
        checkMarginRule.setOnCheckedChangeListener { _, checked ->
            if (checked != spec.marginRule) update(spec.copy(marginRule = checked))
        }
        btnUseOnce.setOnClickListener { useOnce() }
        btnSave.setOnClickListener { startSave() }
    }

    /** One place where a new spec lands: state, then the controls, then the paper. */
    private fun update(next: TemplateSpec) {
        spec = next.sanitized()
        renderControls()
        schedulePreview()
    }

    private fun renderControls() {
        val counting = spec.rows.mode == DensityMode.COUNT
        binding.btnModeSpacing.isSelected = !counting
        binding.btnModeCount.isSelected = counting

        val (rowCount, colCount) = TemplateGeometry.countsFor(spec, pageWidthPx, pageHeightPx, dpi())
        rows.show(if (counting) spec.rows.count.toFloat() else spec.rows.spacingMm)
        rows.readout(axisReadout(vertical = true, counting = counting, count = rowCount))
        if (spec.kind != TemplateKind.LINED) {
            cols.show(if (counting) spec.cols.count.toFloat() else spec.cols.spacingMm)
            cols.readout(axisReadout(vertical = false, counting = counting, count = colCount))
        }
        cols.extraSelected(square)

        insetTop.show(spec.topMm)
        insetBottom.show(spec.bottomMm)
        insetLeft.show(spec.leftMm)
        insetRight.show(spec.rightMm)
        thickness.show(spec.thicknessMm)
        dot.show(spec.dotMm)
        shade.show(spec.shade.toFloat())
        if (binding.checkMarginRule.isChecked != spec.marginRule) {
            binding.checkMarginRule.isChecked = spec.marginRule
        }
    }

    /** In spacing mode the read-out is the count it works out to, and the other way round. */
    private fun axisReadout(vertical: Boolean, counting: Boolean, count: Int): String =
        if (counting) getString(R.string.options_readout_mm, spacingMmOf(vertical))
        else getString(
            if (vertical && spec.kind == TemplateKind.LINED) R.string.options_readout_lines
            else if (vertical) R.string.options_readout_rows
            else R.string.options_readout_columns,
            count,
        )

    // ── Density ──────────────────────────────────────────────────────────────

    /** The spacing one axis actually resolves to on this page, in millimetres. */
    private fun spacingMmOf(vertical: Boolean): Float {
        val axis = if (vertical) spec.rows else spec.cols
        if (axis.mode == DensityMode.SPACING) return axis.spacingMm
        val extent = TemplateGeometry.contentExtentPx(spec, pageWidthPx, pageHeightPx, dpi(), vertical)
        return TemplateGeometry.spacingMmFor(
            axis.count, extent, TemplateGeometry.leadingFor(spec.kind, vertical), dpi(),
        )
    }

    /**
     * Switch what both axes are stated in, **carrying the reading across**: the counts a spacing
     * produces become the counts, and the spacing a count produces becomes the spacing.
     *
     * The count is carried exactly; the spacing is not, and cannot be. A count spreads its features
     * evenly over the page, so 10.0 mm → 14 lines → 9.9 mm: the fourteen lines are the same
     * fourteen, redistributed to fill. That is what a count *means*, and it is why the two modes
     * are two statements rather than two views of one number.
     */
    private fun setMode(mode: DensityMode) {
        if (spec.rows.mode == mode) return
        val next = if (mode == DensityMode.COUNT) {
            val (r, c) = TemplateGeometry.countsFor(spec, pageWidthPx, pageHeightPx, dpi())
            spec.copy(
                rows = spec.rows.copy(mode = mode, count = r.coerceAtLeast(TemplateSpec.MIN_COUNT)),
                cols = spec.cols.copy(mode = mode, count = c.coerceAtLeast(TemplateSpec.MIN_COUNT)),
            )
        } else {
            spec.copy(
                rows = spec.rows.copy(mode = mode, spacingMm = spacingMmOf(true)),
                cols = spec.cols.copy(mode = mode, spacingMm = spacingMmOf(false)),
            )
        }
        spec = next.sanitized()
        // The bounds and the step are the mode's, not the row's, so the two steppers are rebuilt —
        // and the old pair is released first, or a repeat still in flight would drive a stepper
        // nobody can see any more.
        rows.release()
        cols.release()
        rows = densityStepper(binding.stepRows, vertical = true)
        cols = densityStepper(binding.stepCols, vertical = false)
        rows.label(getString(if (spec.kind == TemplateKind.LINED) R.string.options_lines else R.string.options_rows))
        cols.label(getString(R.string.options_columns))
        cols.extra(getString(R.string.options_square)) { toggleSquare() }
        cols.visible(spec.kind != TemplateKind.LINED)
        renderControls()
        schedulePreview()
    }

    private fun onAxisChanged(vertical: Boolean, count: Int?, spacingMm: Float?) {
        val axis = if (vertical) spec.rows else spec.cols
        val next = axis.copy(
            count = count ?: axis.count,
            spacingMm = spacingMm ?: axis.spacingMm,
        )
        var s = if (vertical) spec.copy(rows = next) else spec.copy(cols = next)
        if (square && spec.kind != TemplateKind.LINED) s = mirrored(s, from = vertical)
        update(s)
    }

    private fun toggleSquare() {
        square = !square
        if (square) update(mirrored(spec, from = true)) else renderControls()
    }

    /**
     * Make the other axis carry the same **physical** spacing as [from]. Square cells are a
     * millimetre statement, not a count one: a page that is taller than it is wide needs more rows
     * than columns to hold square cells, so in count mode the mirror is worked out by asking the
     * geometry what that spacing comes to on this page rather than by copying the number across.
     */
    private fun mirrored(source: TemplateSpec, from: Boolean): TemplateSpec {
        val mm = run {
            val axis = if (from) source.rows else source.cols
            if (axis.mode == DensityMode.SPACING) axis.spacingMm else {
                val extent = TemplateGeometry.contentExtentPx(source, pageWidthPx, pageHeightPx, dpi(), from)
                TemplateGeometry.spacingMmFor(
                    axis.count, extent, TemplateGeometry.leadingFor(source.kind, from), dpi(),
                )
            }
        }.coerceIn(TemplateSpec.MIN_SPACING_MM, TemplateSpec.MAX_SPACING_MM)

        val target = if (from) source.cols else source.rows
        val updated = if (target.mode == DensityMode.SPACING) {
            target.copy(spacingMm = mm)
        } else {
            // Ask the geometry what that spacing draws here, then state it as that many.
            val probe = if (from) source.copy(cols = target.copy(mode = DensityMode.SPACING, spacingMm = mm))
                        else source.copy(rows = target.copy(mode = DensityMode.SPACING, spacingMm = mm))
            val (r, c) = TemplateGeometry.countsFor(probe, pageWidthPx, pageHeightPx, dpi())
            target.copy(count = (if (from) c else r).coerceAtLeast(TemplateSpec.MIN_COUNT))
        }
        return if (from) source.copy(cols = updated) else source.copy(rows = updated)
    }

    // ── Preview ──────────────────────────────────────────────────────────────

    private fun dpi(): Float = resources.displayMetrics.densityDpi.toFloat()

    private fun measurePreview() {
        val frameW = binding.previewFrame.width - binding.previewFrame.paddingLeft - binding.previewFrame.paddingRight
        val frameH = binding.previewFrame.height - binding.previewFrame.paddingTop - binding.previewFrame.paddingBottom
        if (frameW <= 0 || frameH <= 0 || pageWidthPx <= 0 || pageHeightPx <= 0) return
        previewMeasured = true
        previewScale = minOf(frameW / pageWidthPx.toFloat(), frameH / pageHeightPx.toFloat())
        previewW = (pageWidthPx * previewScale).toInt().coerceAtLeast(1)
        previewH = (pageHeightPx * previewScale).toInt().coerceAtLeast(1)
        Slog.d(TAG) { "preview ${previewW}x$previewH at scale $previewScale of page ${pageWidthPx}x$pageHeightPx" }
        schedulePreview()
    }

    /**
     * Redraw after the hand settles. A held stepper fires every 110 ms; an EPD panel cannot draw
     * that fast and the user would be watching a queue drain rather than their paper.
     */
    private fun schedulePreview() {
        if (!previewMeasured) return
        previewJob?.cancel()
        val target = spec
        previewJob = lifecycleScope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            val bitmap = withContext(Dispatchers.Default) { renderPreview(target) } ?: return@launch
            binding.preview.setImageBitmap(bitmap)
        }
    }

    private fun renderPreview(target: TemplateSpec): Bitmap? {
        val bmp = BuiltInTemplates.miniature(target, previewW, previewH, previewScale, dpi()) ?: return null
        // The page's own edge, drawn ON the bitmap — a border behind a fit-centred image is
        // overpainted wherever the two disagree (the page-card lesson).
        Canvas(bmp).drawRect(0.5f, 0.5f, bmp.width - 0.5f, bmp.height - 0.5f, border)
        return bmp
    }

    private val border = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    // ── Exits ────────────────────────────────────────────────────────────────

    /** Hand the spec back to whatever asked for a pick. Stores nothing — that is the whole point. */
    private fun useOnce() {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SPEC, spec.encode()))
        finish()
    }

    /**
     * Folder first, then name: the name dialog can then run the whole check — charset, the
     * reserved root name, and the duplicate in *that* folder — and a rejection keeps the typing,
     * which is the same order New folder uses.
     */
    private fun startSave() = folderPickerLauncher.launch(
        FolderPickerActivity.pickIntent(
            this,
            browseFolderType = ObjectType.TEMPLATE_FOLDER,
            rootLabel = getString(R.string.templates_title),
            title = getString(R.string.options_save_title),
            confirm = getString(R.string.options_save_here),
            startAt = startFolderId,
        )
    )

    private fun showNameDialog() {
        val parentId = saveFolderId
        NameDialog.show(
            this,
            titleRes = R.string.options_save_title,
            confirmRes = R.string.options_save_confirm,
            hintRes = R.string.options_save_hint,
        ) { name, dismiss ->
            if (saving) return@show
            if (TemplateNaming.reject(this, name, parentId)) return@show
            saving = true
            lifecycleScope.launch {
                try {
                    if (repo.nameTaken(parentId, ObjectType.TEMPLATE, name)) {
                        Dialogs.problem(
                            this@TemplateOptionsActivity, R.string.name_problem_title,
                            getString(R.string.rename_duplicate_template, name),
                        )
                        return@launch
                    }
                    // The payload is the **spec**, not a render: a variant has to land correctly on
                    // a page it has never seen, which a bitmap could not do.
                    repo.createTemplate(
                        name = name, parentId = parentId, kind = spec.kind.name,
                        fit = 0, payload = spec.encode(),
                    )
                    dismiss()
                    Toast.makeText(this@TemplateOptionsActivity, R.string.options_saved, Toast.LENGTH_SHORT).show()
                    setResult(Activity.RESULT_OK)
                    finish()
                } finally {
                    saving = false
                }
            }
        }
    }

    private fun titleFor(kind: TemplateKind): Int = when (kind) {
        TemplateKind.DOTTED -> R.string.template_dotted
        TemplateKind.GRID -> R.string.template_grid
        else -> R.string.template_lined
    }

    companion object {
        private const val TAG = "TemplateOptions"

        private const val EXTRA_KIND = "kind"
        private const val EXTRA_START_FOLDER = "startFolder"
        private const val EXTRA_PENDING_PICK = "pendingPick"
        private const val EXTRA_PAGE_WIDTH = "pageWidth"
        private const val EXTRA_PAGE_HEIGHT = "pageHeight"

        /** The spec, both ways: in as a starting point, out as the answer to *Use once*. */
        const val EXTRA_SPEC = "spec"

        const val SPACING_STEP_MM = 0.5f
        const val INSET_STEP_MM = 0.5f
        const val THICKNESS_STEP_MM = 0.05f
        const val DOT_STEP_MM = 0.05f

        /** Long enough that a held stepper draws once at the end, short enough to feel immediate. */
        const val PREVIEW_DEBOUNCE_MS = 180L

        fun intent(
            context: Context,
            kind: TemplateKind,
            startFolderId: String? = null,
            pendingPick: Boolean = false,
            spec: TemplateSpec? = null,
            pageWidthPx: Int = 0,
            pageHeightPx: Int = 0,
        ): Intent = Intent(context, TemplateOptionsActivity::class.java)
            .putExtra(EXTRA_KIND, kind.name)
            .putExtra(EXTRA_START_FOLDER, startFolderId)
            .putExtra(EXTRA_PENDING_PICK, pendingPick)
            .putExtra(EXTRA_PAGE_WIDTH, pageWidthPx)
            .putExtra(EXTRA_PAGE_HEIGHT, pageHeightPx)
            .apply { spec?.let { putExtra(EXTRA_SPEC, it.encode()) } }
    }
}
