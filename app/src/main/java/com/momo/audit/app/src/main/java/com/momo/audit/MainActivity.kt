package com.momo.audit

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

data class Entry(val type: String, val time: Long, val size: Long, val path: String)

enum class Sort(val label: String) {
    NEWEST("Newest"), OLDEST("Oldest"), NAME("Name"), SIZE("Size")
}

class MainActivity : ComponentActivity() {

    private var service: IFileService? = null
    private var status by mutableStateOf("Checking Shizuku...")
    private var ready by mutableStateOf(false)
    private var scanning by mutableStateOf(false)
    private var summary by mutableStateOf("")
    private var entries by mutableStateOf<List<Entry>>(emptyList())

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                service = IFileService.Stub.asInterface(binder)
                runOnUiThread { ready = true; status = "Shizuku connected. Ready." }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            runOnUiThread { ready = false; status = "Service disconnected. Reopen the app." }
        }
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, FileService::class.java.name))
            .daemon(false)
            .processNameSuffix("service")
            .version(1)
    }

    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { check() } }
    private val permListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { check() } }

    private fun check() {
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

    private fun runScan(path: String) {
        val s = service ?: return
        scanning = true
        summary = "Scanning..."
        entries = emptyList()
        thread {
            try {
                val head = s.scan(path.trim())
                if (!head.startsWith("OK")) {
                    runOnUiThread { summary = head; scanning = false }
                    return@thread
                }
                val p = head.split("|")
                val list = ArrayList<Entry>()
                var offset = 0
                while (true) {
                    val chunk = s.getChunk(offset)
                    if (chunk.isEmpty()) break
                    for (line in chunk.split('\n')) {
                        if (line.isEmpty()) continue
                        val f = line.split('\t', limit = 4)
                        if (f.size == 4) {
                            list.add(Entry(f[0], f[1].toLongOrNull() ?: 0L, f[2].toLongOrNull() ?: 0L, f[3]))
                        }
                    }
                    offset += chunk.count { it == '\n' }
                }
                val text = "Files ${p[1]}  •  Folders ${p[2]}  •  Size ${fmtSize(p[3].toLongOrNull() ?: 0L)}  •  Unreadable ${p[4]}"
                runOnUiThread { entries = list; summary = text; scanning = false }
            } catch (e: Exception) {
                runOnUiThread { summary = "Error: ${e.message}"; scanning = false }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addRequestPermissionResultListener(permListener)
        setContent {
            MaterialTheme { Screen() }
        }
        check()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    @Composable
    fun Screen() {
        var path by rememberSaveable { mutableStateOf("/sdcard/Android/data") }
        var sort by rememberSaveable { mutableStateOf(Sort.NEWEST) }
        var query by rememberSaveable { mutableStateOf("") }
        var afterText by rememberSaveable { mutableStateOf("") }
        var beforeText by rememberSaveable { mutableStateOf("") }
        var showFolders by rememberSaveable { mutableStateOf(true) }

        val shown = remember(entries, sort, query, afterText, beforeText, showFolders) {
            val after = parseDate(afterText)
            val before = parseDate(beforeText)
            val unread = entries.filter { it.type == "U" }
            var list = entries.filter { it.type != "U" }
            if (!showFolders) list = list.filter { it.type == "F" }
            if (query.isNotBlank()) list = list.filter { it.path.contains(query.trim(), ignoreCase = true) }
            if (after != null) list = list.filter { it.time >= after }
            if (before != null) list = list.filter { it.time <= before }
            list = when (sort) {
                Sort.NEWEST -> list.sortedByDescending { it.time }
                Sort.OLDEST -> list.sortedBy { it.time }
                Sort.NAME -> list.sortedBy { it.path.lowercase() }
                Sort.SIZE -> list.sortedByDescending { it.size }
            }
            unread + list
        }

        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text("Momo Audit", style = MaterialTheme.typography.titleLarge)
            Text(status, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = path, onValueChange = { path = it },
                label = { Text("Start path") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { runScan(path) },
                enabled = ready && !scanning,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (scanning) "Scanning..." else "Scan") }

            Text(summary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (s in Sort.values()) {
                    if (s == sort) Button(onClick = { sort = s }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(s.label, fontSize = 12.sp) }
                    else OutlinedButton(onClick = { sort = s }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(s.label, fontSize = 12.sp) }
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Search name or path") }, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = afterText, onValueChange = { afterText = it },
                    label = { Text("After yyyy-MM-dd HH:mm") }, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = beforeText, onValueChange = { beforeText = it },
                    label = { Text("Before yyyy-MM-dd HH:mm") }, modifier = Modifier.weight(1f)
                )
            }
            Row {
                Switch(checked = showFolders, onCheckedChange = { showFolders = it })
                Spacer(Modifier.width(8.dp))
                Text("Show folders  •  Showing ${shown.size}", fontSize = 12.sp)
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown) { e ->
                    Column(Modifier.padding(vertical = 5.dp)) {
                        val tag = when (e.type) { "D" -> "[FOLDER] "; "U" -> "[UNREADABLE] "; else -> "" }
                        Text(tag + e.path, fontSize = 12.sp)
                        if (e.type != "U") {
                            Text("${fmtTime(e.time)}  (${ago(e.time)})  •  ${fmtSize(e.size)}", fontSize = 11.sp)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

fun parseDate(s: String): Long? {
    if (s.isBlank()) return null
    return try {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        f.isLenient = false
        f.parse(s.trim())?.time
    } catch (e: Exception) { null }
}

fun fmtTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

fun fmtSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
}

fun ago(ms: Long): String {
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 0 -> "in the future"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}
