package com.symmetricalpalmtree.notesproutsn.crypto

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

/**
 * The file operations the rekey commit and its recovery are written against (arc 26 / U2) — an
 * interface so the **ordering** of renames, deletes and fsyncs can be proven on the JVM over a
 * fake ([RekeyCommit], [RekeyRecovery]) while the device runs [RealRekeyFs]. Every method is total:
 * a failure is a `false`, never an exception, so the callers' state machines see every branch.
 *
 * Names are [File]s throughout; a fake keys on the path and never touches disk.
 */
interface RekeyFs {
    fun exists(file: File): Boolean
    fun length(file: File): Long
    /** Atomic within one directory on every filesystem Android puts the Garden on. */
    fun rename(from: File, to: File): Boolean
    fun delete(file: File): Boolean
    /** Flush [file]'s bytes to the medium; a false is logged by the caller, never fatal. */
    fun fsync(file: File): Boolean
    /** Flush the directory entry table after a rename; best effort everywhere. */
    fun fsyncDir(dir: File): Boolean
}

/** The device implementation — `java.io` plus two fsyncs. */
object RealRekeyFs : RekeyFs {
    private const val TAG = "RekeyFs"

    override fun exists(file: File): Boolean = file.exists()
    override fun length(file: File): Long = if (file.exists()) file.length() else 0L
    override fun rename(from: File, to: File): Boolean = try { from.renameTo(to) } catch (_: Exception) { false }
    override fun delete(file: File): Boolean = try { !file.exists() || file.delete() } catch (_: Exception) { false }

    override fun fsync(file: File): Boolean = try {
        RandomAccessFile(file, "rw").use { it.fd.sync() }
        true
    } catch (e: Exception) {
        Log.w(TAG, "fsync failed: ${e.javaClass.simpleName}")
        false
    }

    override fun fsyncDir(dir: File): Boolean = try {
        FileInputStream(dir).use { it.fd.sync() }
        true
    } catch (_: Exception) {
        // Opening a directory for read is not portable across every kernel/filesystem pairing;
        // the renames above are already atomic, this only shortens the window before they persist.
        false
    }
}
