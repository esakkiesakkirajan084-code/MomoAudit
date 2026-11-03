package com.momo.audit

import java.io.File

class FileService : IFileService.Stub() {

    private val lines = ArrayList<String>()
    private val visited = HashSet<String>()
    private var files = 0L
    private var dirs = 0L
    private var bytes = 0L
    private var unreadable = 0L

    override fun destroy() {
        System.exit(0)
    }

    override fun scan(rootPath: String): String {
        lines.clear()
        visited.clear()
        files = 0; dirs = 0; bytes = 0; unreadable = 0
        val root = File(rootPath)
        if (!root.exists()) return "ERROR|Path does not exist"
        walk(root)
        return "OK|$files|$dirs|$bytes|$unreadable"
    }

    override fun getChunk(offset: Int): String {
        val sb = StringBuilder()
        var i = offset
        while (i < lines.size) {
            if (sb.length > 200_000) break
            sb.append(lines[i]).append('\n')
            i++
        }
        return sb.toString()
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    // Returns the newest modified time found in this folder
    private fun walk(dir: File): Long {
        val canon = try { dir.canonicalPath } catch (e: Exception) { dir.path }
        if (!visited.add(canon)) {
            unreadable++
            lines.add("U\t0\t0\t${clean(dir.path)} :: symlink loop skipped")
            return 0L
        }
        dirs++
        var latest = dir.lastModified()
        val index = lines.size
        lines.add("")

        val children = dir.listFiles()
        if (children == null) {
            unreadable++
            lines.add("U\t0\t0\t${clean(dir.path)} :: cannot list folder")
        } else {
            for (c in children) {
                if (c.isDirectory) {
                    val m = walk(c)
                    if (m > latest) latest = m
                } else {
                    files++
                    val size = c.length()
                    val m = c.lastModified()
                    bytes += size
                    if (m > latest) latest = m
                    lines.add("F\t$m\t$size\t${clean(c.path)}")
                }
            }
        }
        lines[index] = "D\t$latest\t0\t${clean(dir.path)}"
        return latest
    }
}
