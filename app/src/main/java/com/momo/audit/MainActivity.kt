package com.momo.audit

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

data class Rec(
    val type: String,
    val mtime: Long,
    val size: Long,
    val atime: Long,
    val ctime: Long,
    val ino: Long
)

data class Ev(val time: Long, val kind: String, val isDir: Boolean, val path: String)

data class Row(val path: String, val mtime: Long, val atime: Long, val size: Long)

val NO_EVENTS: List<Ev> = emptyList()
val NO_ROWS: List<Row> = emptyList()

const val KEEP_MS = 10L * 24L * 60L * 60L * 1000L

fun tabOf(kind: String): Int = when (kind) {
    "OPENED" -> 1
    "DELETED" -> 3
    "NEW" -> 4
    else -> 2
}

class MainActivity : ComponentActivity() {

    private var service: IFileService? = null
    private var status by mutableStateOf("Checking Shizuku...")
    private var ready by mutableStateOf(false)
    private var scanning by mutableStateOf(false)
    private var summary by mutableStateOf("")
    private var events by mutableStateOf(NO_EVENTS)
    private var rows by mutableStateOf(NO_ROWS)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                service = IFileService.Stub.asInterface(binder)
                runOnUiThread {
                    ready = true
                    status = "Shizuku connected."
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            runOnUiThread {
                ready = false
                status = "Service disconnected. Reopen the app."
            }
        }
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, FileService::class.java.name))
            .daemon(false)
            .processNameSuffix("service")
            .version(1)
    }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { checkShizuku() }
    }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { checkShizuku() }
    }

    private fun checkShizuku() {
        if (!Shizuku.pingBinder()) {
            ready = false
            status = "Shizuku is not running. Start it, then reopen this app."
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            if (service == null) {
                status = "Connecting..."
                Shizuku.bindUserService(args, conn)
            }
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            status = "Permission denied. Allow Momo Audit inside the Shizuku app."
        } else {
            Shizuku.requestPermission(100)
        }
    }

    private fun logFile(): File = File(filesDir, "events.txt")

    private fun snapFile(i: Int): File = File(filesDir, "snap_" + i + ".txt")

    private fun loadEvents(): List<Ev> {
        val out = ArrayList<Ev>()
        val f = logFile()
        if (!f.exists()) return out
        val cutoff = System.currentTimeMillis() - KEEP_MS
        try {
            f.forEachLine { line ->
                val p = line.split('\t', limit = 4)
                if (p.size == 4) {
                    val t = p[0].toLongOrNull() ?: 0L
                    if (t >= cutoff) {
                        out.add(Ev(t, p[1], p[2] == "1", p[3]))
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun saveEvents(list: List<Ev>) {
        try {
            logFile().bufferedWriter().use { w ->
                for (e in list) {
                    w.write(
                        e.time.toString() + "\t" + e.kind + "\t" +
                            (if (e.isDir) "1" else "0") + "\t" + e.path + "\n"
                    )
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun parseLine(line: String, into: HashMap<String, Rec>) {
        val f = line.split('\t', limit = 7)
        if (f.size == 7) {
            into[f[6]] = Rec(
                f[0],
                f[1].toLongOrNull() ?: 0L,
                f[2].toLongOrNull() ?: 0L,
                f[3].toLongOrNull() ?: 0L,
                f[4].toLongOrNull() ?: 0L,
                f[5].toLongOrNull() ?: 0L
            )
        }
    }

    private fun loadSnap(i: Int): HashMap<String, Rec>? {
        val f = snapFile(i)
        if (!f.exists()) return null
        val m = HashMap<String, Rec>()
        try {
            f.forEachLine { line -> parseLine(line, m) }
        } catch (e: Exception) {
            return null
        }
        return m
    }

    private fun saveSnap(i: Int, m: HashMap<String, Rec>) {
        try {
            snapFile(i).bufferedWriter().use { w ->
                for ((path, r) in m) {
                    w.write(
                        r.type + "\t" + r.mtime + "\t" + r.size + "\t" + r.atime +
                            "\t" + r.ctime + "\t" + r.ino + "\t" + path + "\n"
                    )
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun readAll(s: IFileService, root: String, cur: HashMap<String, Rec>): String? {
        val head = s.scan(root)
        if (!head.startsWith("OK")) return head
        var offset = 0
        while (true) {
            val chunk = s.getChunk(offset)
            if (chunk.isEmpty()) break
            for (line in chunk.split('\n')) {
                if (line.isNotEmpty()) parseLine(line, cur)
            }
            offset += chunk.count { it == '\n' }
        }
        return null
    }

    private fun runScan(idx: Int, root: String) {
        val s = service ?: return
        if (scanning) return
        scanning = true
        summary = "Scanning..."
        thread {
            try {
                val cur = HashMap<String, Rec>()
                val err = readAll(s, root, cur)
                if (err != null) {
                    runOnUiThread {
                        summary = err
                        scanning = false
                    }
                    return@thread
                }
                val now = System.currentTimeMillis()
                val cutoff = now - KEEP_MS

                val recent = ArrayList<Row>()
                for ((path, c) in cur) {
                    if (c.type == "F" && c.mtime * 1000L >= cutoff) {
                        recent.add(Row(path, c.mtime * 1000L, c.atime * 1000L, c.size))
                    }
                }
                val sortedRows = recent.sortedByDescending { it.mtime }.take(5000)

                val prev = loadSnap(idx)
                val found = ArrayList<Ev>()
                if (prev != null) {
                    for ((path, c) in cur) {
                        val o = prev[path]
                        val isDir = c.type == "D"
                        if (o == null) {
                            found.add(Ev(c.ctime * 1000L, "NEW", isDir, path))
                        } else if (!isDir) {
                            if (o.ino != c.ino) {
                                found.add(Ev(c.ctime * 1000L, "REPLACED", false, path))
                            } else if (o.mtime != c.mtime || o.size != c.size) {
                                found.add(Ev(c.mtime * 1000L, "MODIFIED", false, path))
                            } else if (o.ctime != c.ctime) {
                                found.add(Ev(c.ctime * 1000L, "CHANGED", false, path))
                            } else if (o.atime != c.atime) {
                                found.add(Ev(c.atime * 1000L, "OPENED", false, path))
                            }
                        } else if (o.atime != c.atime) {
                            found.add(Ev(c.atime * 1000L, "OPENED", true, path))
                        }
                    }
                    for ((path, o) in prev) {
                        if (!cur.containsKey(path)) {
                            found.add(Ev(now, "DELETED", o.type == "D", path))
                        }
                    }
                }
                val merged = (found + loadEvents()).filter { it.time >= cutoff }.take(30000)
                saveEvents(merged)
                saveSnap(idx, cur)
                var fileCount = 0
                for (r in cur.values) {
                    if (r.type == "F") fileCount++
                }
                val text = if (prev == null) {
                    "Baseline saved (" + fileCount + " files). Scan again to see changes."
                } else {
                    "Scan " + fmtClock(now) + ": " + found.size + " new changes, " +
                        fileCount + " files watched."
                }
                runOnUiThread {
                    events = merged
                    rows = sortedRows
                    summary = text
                    scanning = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    summary = "Error: " + e.message
                    scanning = false
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        events = loadEvents()
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addRequestPermissionResultListener(permListener)
        setContent {
            MaterialTheme { Screen() }
        }
        checkShizuku()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    @Composable
    fun Screen() {
        var scopeIdx by rememberSaveable { mutableStateOf(1) }
        var tab by rememberSaveable { mutableStateOf(0) }
        var auto by rememberSaveable { mutableStateOf(false) }
        val names = listOf("All storage", "Android/data", "Download")
        val roots = listOf("/sdcard", "/sdcard/Android/data", "/sdcard/Download")
        val tabNames = listOf("Recent", "Opened", "Modified", "Deleted", "New")

        LaunchedEffect(ready, scopeIdx) {
            if (ready) {
                runScan(scopeIdx, roots[scopeIdx])
            }
        }

        LaunchedEffect(auto, ready, scopeIdx) {
            while (auto && ready) {
                delay(5000L)
                runScan(scopeIdx, roots[scopeIdx])
            }
        }

        val counts = remember(events, rows) {
            val c = IntArray(5)
            c[0] = rows.size
            for (e in events) c[tabOf(e.kind)]++
            c
        }
        val shown = remember(events, tab) {
            events.filter { tabOf(it.kind) == tab }.sortedByDescending { it.time }
        }

        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text("Momo Audit", style = MaterialTheme.typography.titleLarge)
            Text(status, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in names.indices) {
                    if (i == scopeIdx) {
                        Button(
                            onClick = { scopeIdx = i },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text(names[i], fontSize = 12.sp) }
                    } else {
                        OutlinedButton(
                            onClick = { scopeIdx = i },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text(names[i], fontSize = 12.sp) }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { runScan(scopeIdx, roots[scopeIdx]) },
                enabled = ready && !scanning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (scanning) "Scanning..." else "Scan")
            }
            Row {
                Switch(checked = auto, onCheckedChange = { auto = it })
                Spacer(Modifier.width(8.dp))
                Text("Auto scan every 5 seconds", fontSize = 12.sp)
            }
            Text(summary, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (i in tabNames.indices) {
                    val label = tabNames[i] + " " + counts[i]
                    if (i == tab) {
                        Button(
                            onClick = { tab = i },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) { Text(label, fontSize = 10.sp, maxLines = 1) }
                    } else {
                        OutlinedButton(
                            onClick = { tab = i },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) { Text(label, fontSize = 10.sp, maxLines = 1) }
                    }
                }
            }
            HorizontalDivider()

            if (tab == 0) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows) { r ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                fmtTime(r.mtime) + "  MODIFIED",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                fmtTime(r.atime) + "  ACCESSED  " + fmtSize(r.size),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(r.path, fontSize = 11.sp)
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown) { e ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            val what = if (e.isDir) e.kind + " FOLDER" else e.kind + " FILE"
                            Text(
                                fmtTime(e.time) + "  " + what,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(e.path, fontSize = 11.sp)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

fun fmtTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

fun fmtClock(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

fun fmtSize(b: Long): String = when {
    b < 1024L -> b.toString() + " B"
    b < 1048576L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    b < 1073741824L -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
}
