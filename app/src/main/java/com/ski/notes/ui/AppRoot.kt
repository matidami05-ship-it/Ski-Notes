package com.ski.notes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ski.notes.data.NoteRepository
import com.ski.notes.data.Prefs

@Composable
fun AppRoot(initialCrash: String?, incoming: Pair<android.net.Uri, String?>? = null, onIncomingHandled: () -> Unit = {}) {
    val ctx = LocalContext.current
    val repo = remember { NoteRepository(ctx.applicationContext) }
    val prefs = remember { Prefs(ctx.applicationContext) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var newNote by rememberSaveable { mutableStateOf(false) }
    var crash by remember { mutableStateOf(initialCrash) }
    var listVersion by remember { mutableStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun importFile(uri: android.net.Uri, mime: String?) {
        if (importing) return
        importing = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { repo.import(uri, mime) } }
            importing = false
            r.onSuccess { imp ->
                imp.warning?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                newNote = false
                openId = imp.id
            }.onFailure {
                Toast.makeText(ctx, it.message ?: "Couldn't open that file", Toast.LENGTH_LONG).show()
                listVersion++
            }
        }
    }

    // A file sent from another app ("Open with Ski Notes" / Share).
    LaunchedEffect(incoming) {
        val inc = incoming ?: return@LaunchedEffect
        onIncomingHandled()
        openId = null
        importFile(inc.first, inc.second)
    }

    val id = openId
    if (id == null) {
        NotesListScreen(
            repo = repo,
            prefs = prefs,
            version = listVersion,
            onOpen = { openId = it; newNote = false },
            onImport = { uri, mime -> importFile(uri, mime) },
            onNew = {
                val d = repo.create()
                d.paper = prefs.defaultPaper
                d.tone = prefs.defaultTone
                repo.save(d)
                newNote = true
                openId = d.id
            },
        )
    } else {
        BackHandler { openId = null; listVersion++ }
        androidx.compose.runtime.key(id) { EditorScreen(
            id = id,
            isNew = newNote,
            repo = repo,
            prefs = prefs,
            onClose = { openId = null; listVersion++ },
        ) }
    }

    if (importing) {
        Dialog(onDismissRequest = {}) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Opening file…")
                }
            }
        }
    }

    crash?.let { text ->
        AlertDialog(
            onDismissRequest = { crash = null },
            title = { Text("Ski Notes closed unexpectedly") },
            text = {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Ski Notes crash", text))
                    crash = null
                }) { Text("Copy report") }
            },
            dismissButton = { TextButton(onClick = { crash = null }) { Text("Close") } },
        )
    }
}
