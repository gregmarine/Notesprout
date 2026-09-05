package com.symmetricalpalmtree.notesproutsn.library

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.CloudClient
import com.symmetricalpalmtree.notesproutsn.extension.CloudEntry
import com.symmetricalpalmtree.notesproutsn.extension.ProviderRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Random

/**
 * **The V2 measurement tool** (arc 25 / V2, debug builds only) — the one thing on the device that
 * can fill in [com.symmetricalpalmtree.notesproutsn.extension.CloudTimeouts].
 *
 * Every row in that table is a placeholder marked UNMEASURED, and the plan's rule (W6) is that a
 * budget is sized by the work and never by taste. The work is a network round trip through another
 * process on a Supernote's wifi — nothing on a JVM can stand in for it — so this walks the whole
 * interface once, in order, timing each call, and prints the numbers three ways: on the glass in a
 * dialog, into logcat as `probe: <op> <n> ms` lines a walk can read without touching the screen, and
 * as a summary the tester can copy into the phase note.
 *
 * **It writes real files to the person's cloud** — a 1 MiB and a 20 MiB block of pseudo-random bytes
 * under `Exports/probe/` — and deletes both at the end. Its local cache files are deleted in
 * `finally` whatever happened.
 *
 * A failed step does not stop the run: it is recorded with its exception class and message and the
 * next step goes ahead where that makes sense (a step needing an entry a failed upload never
 * produced is skipped, and says so). Half a table of measurements is worth having.
 */
object CloudProbe {

    const val TAG = "CloudProbe"

    /** The folder the probe writes into, under the provider's own root. */
    private val FOLDER = arrayOf("Exports", "probe")

    private const val SMALL_NAME = "probe-1MiB.bin"
    private const val LARGE_NAME = "probe-20MiB.bin"
    private const val MIME = "application/octet-stream"
    private const val SMALL_BYTES = 1L * 1024 * 1024
    private const val LARGE_BYTES = 20L * 1024 * 1024

    /** One finished step: what it was, how long it took, and what it said. */
    class Step(val op: String, val ms: Long, val detail: String, val failed: Boolean)

    /**
     * Run the whole probe. [onStep] is called on the caller's thread after each step with the line
     * just finished — the progress dialog's text.
     *
     * Answers the report as one block of text. Never throws except [CancellationException].
     */
    suspend fun run(
        context: Context,
        ref: ProviderRef,
        onStep: (Step) -> Unit,
    ): String {
        val steps = ArrayList<Step>()
        val small = File(context.cacheDir, SMALL_NAME)
        val large = File(context.cacheDir, LARGE_NAME)
        val down = File(context.cacheDir, "probe-download.bin")

        suspend fun <T> step(op: String, block: suspend () -> T): T? {
            val t0 = System.currentTimeMillis()
            return try {
                val value = block()
                val ms = System.currentTimeMillis() - t0
                record(steps, Step(op, ms, describe(value), failed = false), onStep)
                value
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val ms = System.currentTimeMillis() - t0
                record(steps, Step(op, ms, "FAILED — ${e.javaClass.simpleName}: ${e.message}", failed = true), onStep)
                null
            }
        }

        try {
            step("status (cold)") { CloudClient.status(context, ref) }
            step("status (warm)") { CloudClient.status(context, ref) }
            step("ensureFolder Exports/probe") { CloudClient.ensureFolder(context, ref, FOLDER) }
            step("list root") { CloudClient.list(context, ref, emptyArray()) }
            step("list Exports") { CloudClient.list(context, ref, arrayOf("Exports")) }

            step("write 1 MiB cache file") { fill(small, SMALL_BYTES) }
            val smallEntry = step("upload 1 MiB") {
                CloudClient.upload(context, ref, FOLDER, SMALL_NAME, MIME, readFd(small), SMALL_BYTES)
                    .also { verify(it, SMALL_BYTES, steps, onStep) }
            }

            step("write 20 MiB cache file") { fill(large, LARGE_BYTES) }
            val largeEntry = step("upload 20 MiB") {
                CloudClient.upload(context, ref, FOLDER, LARGE_NAME, MIME, readFd(large), LARGE_BYTES)
                    .also { verify(it, LARGE_BYTES, steps, onStep) }
            }

            step("list Exports/probe") { CloudClient.list(context, ref, FOLDER) }

            if (largeEntry != null) {
                val written = step("download 20 MiB") {
                    CloudClient.download(context, ref, largeEntry.id, writeFd(down))
                }
                if (written != null) {
                    val onDisk = down.length()
                    record(
                        steps,
                        Step(
                            "download check", 0,
                            "reported $written B, on disk $onDisk B, agrees=${written == LARGE_BYTES && onDisk == LARGE_BYTES}",
                            failed = written != LARGE_BYTES || onDisk != LARGE_BYTES,
                        ),
                        onStep,
                    )
                }
            } else {
                record(steps, Step("download 20 MiB", 0, "skipped — the upload gave no entry", failed = true), onStep)
            }

            if (smallEntry != null) step("delete 1 MiB") { CloudClient.delete(context, ref, smallEntry.id) }
            else record(steps, Step("delete 1 MiB", 0, "skipped — nothing to delete", failed = true), onStep)
            if (largeEntry != null) step("delete 20 MiB") { CloudClient.delete(context, ref, largeEntry.id) }
            else record(steps, Step("delete 20 MiB", 0, "skipped — nothing to delete", failed = true), onStep)
        } finally {
            // The cache is not the person's storage — nothing the probe wrote outlives it.
            for (f in listOf(small, large, down)) runCatching { f.delete() }
        }

        return steps.joinToString("\n") { "${it.op}: ${it.ms} ms — ${it.detail}" }
    }

    private fun record(steps: MutableList<Step>, step: Step, onStep: (Step) -> Unit) {
        steps += step
        // The shape the walk greps for. Counts, byte counts and durations only — the account label
        // is never printed here, and the file names are the probe's own.
        Log.i(TAG, "probe: ${step.op} ${step.ms} ms")
        Slog.d(TAG) { "probe: ${step.op} ${step.ms} ms — ${step.detail}" }
        onStep(step)
    }

    /** The upload's own corroboration, recorded as its own row: does the provider agree about the
     *  size it just took? Disagreement is *check the file*, never delete (the arc-15 rule). */
    private fun verify(entry: CloudEntry, expected: Long, steps: MutableList<Step>, onStep: (Step) -> Unit) {
        record(
            steps,
            Step(
                "upload check", 0,
                "wrote $expected B, provider reports ${entry.sizeBytes} B, agrees=${entry.sizeBytes == expected}",
                failed = entry.sizeBytes != expected,
            ),
            onStep,
        )
    }

    private fun describe(value: Any?): String = when (value) {
        null -> "ok"
        is List<*> -> "${value.size} entries"
        is CloudEntry -> if (value.isFolder) "folder" else "file, ${value.sizeBytes} B"
        is Long -> "$value B"
        else -> value.toString()
    }

    /** [bytes] of pseudo-random content — a fixed seed, so two runs write the same file and any
     *  difference in timing is the network's and not the data's. **On IO**: twenty megabytes of
     *  writes is not something to do on the thread holding the progress dialog. */
    private suspend fun fill(file: File, bytes: Long): Long = withContext(Dispatchers.IO) {
        val random = Random(bytes)
        val buffer = ByteArray(64 * 1024)
        file.outputStream().use { out ->
            var left = bytes
            while (left > 0) {
                random.nextBytes(buffer)
                val n = minOf(left, buffer.size.toLong()).toInt()
                out.write(buffer, 0, n)
                left -= n
            }
        }
        file.length()
    }

    private fun readFd(file: File): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    private fun writeFd(file: File): ParcelFileDescriptor =
        ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE,
        )
}
