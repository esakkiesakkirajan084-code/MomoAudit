package com.momo.audit

import android.system.Os
import java.io.File

class FileService : IFileService.Stub() {

    private val lines = ArrayList<String>()
    private val visited = HashSet<String>()
    private var files = 0L
    private var dirs = 0L
    private var unreadable = 0L

    override fun destroy() {
        System.exit(0)
    }

    override fun scan(rootPath: String): String {
        lines.clear()
        visited.clear()
        files = 0
        dirs = 0
        unreadable = 0
        val root = File(rootPath)
        if (!root.exists()) return "ERROR|Path does not exist"
        walk(root)
        return "OK|" + files + "|" + dirs + "|0|" + unreadable
    }

    override fun getChunk(offset: Int): String {
        val sb = StringBuilder()
        var i = offset
        while (i < lines.size) {
            if (sb.length > 200000) break
            sb.append(lines[i]).append('\n')
            i++
        }
        return sb.toString()
    }

    private fun walk(dir: File) {
        val canon = try {
            dir.canonicalPath
        } catch (e: Exception) {
            dir.path
        }
        if (!visited.add(canon)) return
        dirs++
        add("D", dir)
        val children = dir.listFiles()
        if (children == null) {
            unreadable++
            return
        }
        for (c in children) {
            if (c.isDirectory) {
                walk(c)
            } else {
                files++
                add("F", c)
            }
        }
    }

    private fun add(type: String, f: File) {
        val p = f.path.replace('\t', ' ').replace('\n', ' ')
        try {
            val st = Os.lstat(f.path)
            lines.add(
                type + "\t" + st.st_mtime + "\t" + st.st_size + "\t" +
                    st.st_atime + "\t" + st.st_ctime + "\t" + st.st_ino + "\t" + p
            )
        } catch (e: Exception) {
            lines.add(
                type + "\t" + (f.lastModified() / 1000) + "\t" + f.length() +
                    "\t0\t0\t0\t" + p
            )
        }
    }
}
