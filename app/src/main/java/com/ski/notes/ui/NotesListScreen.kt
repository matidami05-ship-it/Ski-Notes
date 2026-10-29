package com.ski.notes.ui

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ski.notes.data.NoteRepository
import com.ski.notes.data.Prefs
import com.ski.notes.ui.theme.Panel
import com.ski.notes.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotesListScreen(
    repo: NoteRepository,
    prefs: Prefs,
    version: Int,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
) {
    var items by remember { mutableStateOf<List<NoteRepository.Item>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<NoteRepository.Item?>(null) }
    var deleting by remember { mutableStateOf<NoteRepository.Item?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(version, refresh) {
        items = withContext(Dispatchers.IO) { repo.list() }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New note") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding()).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ski Notes", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "S Pen settings") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search notes") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val list = items
            val shown = list?.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
            if (list != null && list.isEmpty()) {
                EmptyState(Modifier.weight(1f).fillMaxWidth())
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(shown.orEmpty(), key = { it.id }) { item ->
                        NoteCard(
                            item = item,
                            onOpen = { onOpen(item.id) },
                            onRename = { renaming = item },
                            onDuplicate = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { repo.duplicate(item.id) }
                                    refresh++
                                }
                            },
                            onDelete = { deleting = item },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("Untitled") }) },
            confirmButton = {
                TextButton(onClick = {
                    val t = text.trim()
                    renaming = null
                    scope.launch {
                        withContext(Dispatchers.IO) { repo.rename(item.id, t) }
                        refresh++
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${item.title.ifBlank { "Untitled" }}\"?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        withContext(Dispatchers.IO) { repo.delete(item.id) }
                        refresh++
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    if (showSettings) SPenSettingsDialog(prefs = prefs, onDismiss = { showSettings = false })
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.Draw, null, modifier = Modifier.size(64.dp), tint = TextDim)
        Spacer(Modifier.height(12.dp))
        Text("No notes yet", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("Tap New note and start writing with your S Pen", color = TextDim)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    item: NoteRepository.Item,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val thumb by produceState<ImageBitmap?>(null, item.id, item.modified) {
        value = withContext(Dispatchers.IO) {
            item.thumb?.let { f -> runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1240f / 1754f)
                .clip(RoundedCornerShape(12.dp))
                .background(Panel)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
        ) {
            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.title.ifBlank { "Untitled" },
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            DateUtils.getRelativeTimeSpanString(item.modified).toString() + " · ${item.pages} page" + if (item.pages == 1) "" else "s",
            color = TextDim,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}
