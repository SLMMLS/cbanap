package com.clipsync.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.clipsync.app.data.DecryptedEntry
import com.clipsync.app.data.Repository
import com.clipsync.app.network.NetworkErrors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(repository: Repository, onSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val entries by repository.observeEntries().collectAsState(initial = emptyList())
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("ClipSync") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Отправить буфер") },
                icon = { Icon(Icons.Filled.Send, contentDescription = null) },
                onClick = {
                    val clip = clipboardManager.getText()?.text
                    if (clip.isNullOrBlank()) {
                        scope.launch { snackbar.showSnackbar("Буфер обмена пуст") }
                    } else {
                        // The old code swallowed every failure here, so a send that never
                        // reached the PC looked exactly like one that did.
                        scope.launch {
                            val result = runCatching { repository.sendToPc("text", clip.toByteArray(Charsets.UTF_8)) }
                            val failure = result.exceptionOrNull()
                            snackbar.showSnackbar(
                                if (failure == null) "Отправлено на ПК" else "Не отправлено: ${NetworkErrors.explain(failure)}",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Пока пусто — отправьте что-нибудь с ПК", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(entries, key = { it.id }) { entry ->
                    EntryRow(
                        entry = entry,
                        onClick = {
                            if (entry.entryType == "text") {
                                clipboardManager.setText(AnnotatedString(String(entry.content, Charsets.UTF_8)))
                            }
                        },
                        onPinToggle = { scope.launch { repository.togglePin(entry.id, !entry.pinned) } },
                        onDelete = { scope.launch { repository.delete(entry.id) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: DecryptedEntry,
    onClick: () -> Unit,
    onPinToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    ListItem(
        headlineContent = {
            Text(
                text = if (entry.entryType == "text") {
                    String(entry.content, Charsets.UTF_8).take(120)
                } else {
                    "Изображение"
                },
                maxLines = 2,
            )
        },
        supportingContent = { Text(timeFormat.format(Date(entry.createdAt))) },
        trailingContent = {
            Row {
                TextButton(onClick = onPinToggle) { Text(if (entry.pinned) "Открепить" else "Закрепить") }
                TextButton(onClick = onDelete) { Text("Удалить") }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
