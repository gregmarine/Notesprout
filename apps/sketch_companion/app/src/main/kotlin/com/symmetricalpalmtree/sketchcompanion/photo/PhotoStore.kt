package com.symmetricalpalmtree.sketchcompanion.photo

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * The one photo the app holds, copied byte-for-byte into `filesDir` (EXIF intact) the moment it
 * arrives — a Photo Picker grant is transient, and the camera's file lives in the cache.
 */
class PhotoStore(private val context: Context) {

    private val cameraDir get() = File(context.cacheDir, "camera").apply { mkdirs() }

    /** The camera always writes here; a fixed name survives the app being evicted mid-capture. */
    fun cameraFile(): File = File(cameraDir, "capture.jpg")

    fun cameraUri(): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cameraFile())

    fun photoFile(name: String): File = File(context.filesDir, name)

    /** Copies [uri] into a fresh `photo-<epoch>.jpg`, drops every older photo and the camera temp,
     *  and answers the new file's name. Call off the main thread. */
    @Throws(IOException::class)
    fun adopt(uri: Uri): String {
        val name = "photo-${System.currentTimeMillis()}.jpg"
        val target = photoFile(name)
        val tmp = File(context.filesDir, "$name.tmp")
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("no stream for $uri")
        input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst) } }
        if (tmp.length() == 0L) { tmp.delete(); throw IOException("empty photo") }
        if (!tmp.renameTo(target)) { tmp.delete(); throw IOException("rename failed") }
        context.filesDir.listFiles()?.forEach {
            if (it.name.startsWith("photo-") && it.name != name) it.delete()
        }
        cameraFile().delete()
        return name
    }
}
