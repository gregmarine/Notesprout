package com.notesprout.android.notebook

import android.content.Context
import android.util.Log
import com.notesprout.android.core.isBooxDevice
import com.notesprout.android.core.isRattaDevice
import com.notesprout.android.notebook.ratta.SupernoteInk

/**
 * The one place engine choice happens. All six drawing hosts (notebook, calendar,
 * day-note, scratch pad, sticky-note editor, HWR enrollment) construct their drawing
 * view through this call — never directly.
 *
 * Engine choice is decided once, at construction; there is no mid-session swap and no
 * kill switch. If the Ratta firmware misbehaves after construction, the view logs and
 * toasts but keeps running (locked no-fallback decision — docs/drawing-engine.md, Ratta section).
 *
 * Ordered so BOOX is decided first (a string compare, no reflection) and the Ratta
 * binder probe only runs on a Supernote device.
 */
fun createNotebookView(context: Context): NotebookView {
    val ratta = isRattaDevice()
    val view = when {
        isBooxDevice() -> OnyxNotebookView(context)
        ratta && SupernoteInk.isAvailable() -> RattaNotebookView(context)
        else -> GenericNotebookView(context)
    }
    // Log.i, not Slog: one line per screen open is worth having in a release bug report.
    Log.i(
        "NotebookViewFactory",
        "engine=${view.javaClass.simpleName} ratta=$ratta" +
            if (ratta) " firmware=${SupernoteInk.isAvailable()}" else ""
    )
    return view
}
