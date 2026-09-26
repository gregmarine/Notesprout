package com.symmetricalpalmtree.sketchcompanion

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.sketchcompanion.crop.CropState
import com.symmetricalpalmtree.sketchcompanion.databinding.ActivityMainBinding
import com.symmetricalpalmtree.sketchcompanion.export.ExportRenderer
import com.symmetricalpalmtree.sketchcompanion.export.ExportSink
import com.symmetricalpalmtree.sketchcompanion.grid.Grid
import com.symmetricalpalmtree.sketchcompanion.grid.GridWeight
import com.symmetricalpalmtree.sketchcompanion.photo.PhotoDecoder
import com.symmetricalpalmtree.sketchcompanion.photo.PhotoStore
import com.symmetricalpalmtree.sketchcompanion.session.Session
import com.symmetricalpalmtree.sketchcompanion.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var photos: PhotoStore
    private lateinit var sessions: SessionStore
    private var session = Session()
    private var srcW = 0
    private var srcH = 0
    private var busy = false

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) adopt(photos.cameraUri())
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) adopt(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        photos = PhotoStore(this)
        sessions = SessionStore(this)

        for (btn in listOf(b.btnCamera, b.btnPhoto, b.btnSave, b.btnShare)) {
            TooltipCompat.setTooltipText(btn, btn.contentDescription)
        }
        b.btnCamera.setOnClickListener {
            try {
                takePicture.launch(photos.cameraUri())
            } catch (e: ActivityNotFoundException) {
                alert(R.string.error_no_camera)
            }
        }
        b.btnPhoto.setOnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        b.btnSave.setOnClickListener { export(share = false) }
        b.btnShare.setOnClickListener { export(share = true) }
        b.frame.onCropChanged = { crop -> update(session.copy(crop = crop), applyToFrame = false) }

        buildPanel()
        applyPanel()
        applyGrid()

        lifecycleScope.launch {
            val loaded = sessions.load()
            session = loaded
            applyPanel(); applyGrid()
            val name = loaded.photoFile ?: return@launch
            val file = photos.photoFile(name)
            if (file.exists()) showPhoto(name, loaded.crop) else update(session.copy(photoFile = null))
        }
    }

    override fun onStop() {
        super.onStop()
        sessions.flushNow()
    }

    // ── Panel ────────────────────────────────────────────────────────────────

    private fun buildPanel() {
        b.rowStyle.setItems(listOf(getString(R.string.grid_off), getString(R.string.grid_lines), getString(R.string.grid_dots)))
        b.rowStyle.onPick = { i -> update(session.copy(gridKind = listOf(Grid.OFF, Grid.LINES, Grid.DOTS)[i])) }

        val rows = Grid.countRows()
        b.rowCountA.setItems(rows[0].map { it.toString() })
        b.rowCountB.setItems(rows[1].map { it.toString() })
        b.rowCountA.onPick = { i -> b.rowCountB.select(-1); update(session.copy(gridCount = rows[0][i])) }
        b.rowCountB.onPick = { i -> b.rowCountA.select(-1); update(session.copy(gridCount = rows[1][i])) }

        val inset = resources.getDimensionPixelSize(R.dimen.swatch_inset)
        val density = resources.displayMetrics.density
        b.rowColor.setItems(Grid.SWATCHES.map { "" }) { button, i ->
            val fill = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 3f * density
                setColor(Grid.SWATCHES[i])
                setStroke((1f * density).toInt(), getColor(R.color.inkBlack))
            }
            val card = getDrawable(R.drawable.bg_selectable_card)!!
            button.background = LayerDrawable(arrayOf(card, fill)).apply { setLayerInset(1, inset, inset, inset, inset) }
            button.contentDescription = "Colour ${i + 1}"
        }
        b.rowColor.onPick = { i -> update(session.copy(gridColor = Grid.SWATCHES[i])) }

        b.rowWeight.setItems(listOf(getString(R.string.weight_thin), getString(R.string.weight_regular), getString(R.string.weight_bold)))
        b.rowWeight.onPick = { i -> update(session.copy(gridWeight = i)) }
    }

    private fun applyPanel() {
        val s = session
        b.rowStyle.select(when (s.gridKind) { Grid.OFF -> 0; Grid.DOTS -> 2; else -> 1 })
        val rows = Grid.countRows()
        b.rowCountA.select(rows[0].indexOf(s.gridCount))
        b.rowCountB.select(rows[1].indexOf(s.gridCount))
        b.rowColor.select(Grid.SWATCHES.indexOf(s.gridColor))
        b.rowWeight.select(s.gridWeight)
        val on = s.gridKind != Grid.OFF
        b.rowCountA.show(on); b.rowCountB.show(on); b.rowColor.show(on); b.rowWeight.show(on)
    }

    private fun applyGrid() {
        val s = session
        b.frame.setGrid(s.gridKind, s.gridCount, s.gridColor, GridWeight.fromOrdinal(s.gridWeight))
    }

    private fun update(next: Session, applyToFrame: Boolean = true) {
        session = next
        if (applyToFrame) { applyPanel(); applyGrid() }
        sessions.save(next)
    }

    // ── Photo ────────────────────────────────────────────────────────────────

    private fun adopt(uri: Uri) {
        lifecycleScope.launch {
            val name = try {
                withContext(Dispatchers.IO) { photos.adopt(uri) }
            } catch (e: Exception) {
                Log.w(TAG, "adopt failed", e); alert(R.string.error_open_photo); return@launch
            }
            update(session.copy(photoFile = name, crop = CropState()))
            showPhoto(name, CropState())
        }
    }

    private suspend fun showPhoto(name: String, crop: CropState) {
        val decoded = try {
            PhotoDecoder.forDisplay(photos.photoFile(name))
        } catch (e: Exception) {
            Log.w(TAG, "decode failed", e); alert(R.string.error_open_photo); return
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "decode OOM", e); alert(R.string.error_open_photo); return
        }
        srcW = decoded.srcW; srcH = decoded.srcH
        b.frame.setPhoto(decoded.bitmap, srcW, srcH, crop)
    }

    // ── Export ───────────────────────────────────────────────────────────────

    private fun export(share: Boolean) {
        val name = session.photoFile
        if (name == null || srcW == 0) { toast(R.string.toast_no_photo); return }
        if (busy) return
        busy = true
        b.status.text = getString(R.string.exporting)
        b.status.visibility = View.VISIBLE
        val s = session
        val spec = ExportRenderer.GridSpec(s.gridKind, s.gridCount, s.gridColor, GridWeight.fromOrdinal(s.gridWeight))
        lifecycleScope.launch {
            var bitmap: Bitmap? = null
            try {
                bitmap = ExportRenderer.render(photos.photoFile(name), srcW, srcH, b.frame.crop, spec)
                if (share) {
                    val uri = ExportSink.shareFile(this@MainActivity, bitmap)
                    startActivity(ExportSink.shareIntent(uri, getString(R.string.action_share)))
                } else {
                    ExportSink.saveToPhotos(this@MainActivity, bitmap)
                    toast(R.string.toast_saved)
                }
            } catch (e: Exception) {
                Log.w(TAG, "export failed", e); alert(R.string.error_export)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "export OOM", e); alert(R.string.error_export)
            } finally {
                bitmap?.recycle()
                b.status.text = ""
                busy = false
            }
        }
    }

    // ── Chrome ───────────────────────────────────────────────────────────────

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun alert(res: Int) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle(R.string.error_title)
            .setMessage(res)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    companion object {
        private const val TAG = "SketchCompanion"
    }
}
