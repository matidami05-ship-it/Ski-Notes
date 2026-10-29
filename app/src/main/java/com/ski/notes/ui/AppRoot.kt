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
import com.ski.notes.data.NoteRepository
import com.ski.notes.data.Prefs

@Composable
fun AppRoot(initialCrash: String?) {
    val ctx = LocalContext.current
    val repo = remember { NoteRepository(ctx.applicationContext) }
    val prefs = remember { Prefs(ctx.applicationContext) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var newNote by rememberSaveable { mutableStateOf(false) }
    var crash by remember { mutableStateOf(initialCrash) }
    var listVersion by remember { mutableStateOf(0) }

    val id = openId
    if (id == null) {
        NotesListScreen(
            repo = repo,
            prefs = prefs,
            version = listVersion,
            onOpen = { openId = it; newNote = false },
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
        EditorScreen(
            id = id,
            isNew = newNote,
            repo = repo,
            prefs = prefs,
            onClose = { openId = null; listVersion++ },
        )
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
