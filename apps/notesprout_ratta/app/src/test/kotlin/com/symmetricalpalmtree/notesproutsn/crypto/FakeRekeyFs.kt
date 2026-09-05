package com.symmetricalpalmtree.notesproutsn.crypto

import java.io.File

/**
 * An in-memory [RekeyFs]: files are path → content tag (a string, so a rename provably moves the
 * *same* bytes), every call is logged in order, and named operations can be made to fail.
 */
class FakeRekeyFs(initial: Map<String, String> = emptyMap()) : RekeyFs {
    val files: MutableMap<String, String> = initial.toMutableMap()
    val log = mutableListOf<String>()
    /** `"rename:<from>-><to>"` / `"delete:<path>"` entries here answer false. */
    val failing = mutableSetOf<String>()

    private fun k(f: File) = f.path

    override fun exists(file: File) = k(file) in files
    override fun length(file: File) = files[k(file)]?.length?.toLong() ?: 0L

    override fun rename(from: File, to: File): Boolean {
        val op = "rename:${k(from)}->${k(to)}"
        log += op
        if (op in failing) return false
        val content = files.remove(k(from)) ?: return false
        files[k(to)] = content
        return true
    }

    override fun delete(file: File): Boolean {
        val op = "delete:${k(file)}"
        log += op
        if (op in failing) return false
        files.remove(k(file))
        return true
    }

    override fun fsync(file: File): Boolean { log += "fsync:${k(file)}"; return true }
    override fun fsyncDir(dir: File): Boolean { log += "fsyncDir:${k(dir)}"; return true }
}
