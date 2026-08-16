package com.symmetricalpalmtree.notesprout.ext.templates

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SharedMemory
import android.system.OsConstants
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.ITemplateProvider
import com.symmetricalpalmtree.notesprout.extension.RenderedTemplate
import com.symmetricalpalmtree.notesprout.extension.TemplateInfo

/**
 * The Templates extension point. Bound by the Notesprout Paper core; never launched by a user (this
 * package declares no Activity). AIDL methods run on Binder threads, so the stub is re-entrant and
 * holds no mutable state.
 */
class TemplateProviderService : Service() {

    private val binder = object : ITemplateProvider.Stub() {

        override fun listTemplates(): List<TemplateInfo> {
            CallerCheck.enforce(this@TemplateProviderService)
            return TemplateRenderer.TEMPLATE_IDS.map { id ->
                TemplateInfo(id, getString(nameResFor(id)))
            }
        }

        override fun render(templateId: String, widthPx: Int, heightPx: Int, dpi: Float): RenderedTemplate? {
            CallerCheck.enforce(this@TemplateProviderService)
            val bytes = TemplateRenderer.renderWebp(templateId, widthPx, heightPx, dpi) ?: return null
            val shared = SharedMemory.create(null, bytes.size)
            val buffer = shared.mapReadWrite()
            buffer.put(bytes)
            SharedMemory.unmap(buffer)
            shared.setProtect(OsConstants.PROT_READ)
            return RenderedTemplate(shared, bytes.size, ExtensionContract.MIME_WEBP)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun nameResFor(id: String): Int = when (id) {
        "lined" -> R.string.template_lined
        "dotted" -> R.string.template_dotted
        "grid" -> R.string.template_grid
        else -> R.string.ext_label
    }
}
