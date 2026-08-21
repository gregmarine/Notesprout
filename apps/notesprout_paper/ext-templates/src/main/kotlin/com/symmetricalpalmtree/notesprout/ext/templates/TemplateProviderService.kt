package com.symmetricalpalmtree.notesprout.ext.templates

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.os.SharedMemory
import android.system.OsConstants
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.HostCallerCheck
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

        /** The region handed out by the render running on this Binder thread; closed once the reply is written. */
        private val pending = ThreadLocal<SharedMemory>()

        /**
         * The reply parcel dups the region's file descriptor when [RenderedTemplate] is written, so
         * the extension's own handle can be closed as soon as the transaction has been marshalled —
         * otherwise every render leaks a descriptor until GC.
         */
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            try {
                return super.onTransact(code, data, reply, flags)
            } finally {
                pending.get()?.close()
                pending.remove()
            }
        }

        override fun listTemplates(): List<TemplateInfo> {
            HostCallerCheck.enforce(this@TemplateProviderService, BuildConfig.HOST_PACKAGE)
            return TemplateRenderer.Kind.entries.map { TemplateInfo(it.id, getString(it.nameRes)) }
        }

        override fun render(templateId: String, widthPx: Int, heightPx: Int, dpi: Float): RenderedTemplate? {
            HostCallerCheck.enforce(this@TemplateProviderService, BuildConfig.HOST_PACKAGE)
            val bytes = TemplateRenderer.renderWebp(templateId, widthPx, heightPx, dpi) ?: return null
            val shared = SharedMemory.create(null, bytes.size)
            val buffer = shared.mapReadWrite()
            buffer.put(bytes)
            SharedMemory.unmap(buffer)
            shared.setProtect(OsConstants.PROT_READ)
            pending.set(shared)
            return RenderedTemplate(shared, bytes.size, ExtensionContract.MIME_WEBP)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
