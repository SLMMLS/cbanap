package com.clipsync.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clipsync.app.data.Repository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: Repository, onBack: () -> Unit, onUnpaired: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pairedPc by repository.pairedPc.collectAsState(initial = null)
    var diagnosis by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Сопряжённый ПК", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            val pc = pairedPc
            if (pc == null) {
                Text("Нет подключённого ПК")
            } else {
                Text(pc.name)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { scope.launch { repository.unpair(); onUnpaired() } }) {
                    Text("Отключить")
                }

                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    enabled = !checking,
                    onClick = {
                        scope.launch {
                            checking = true
                            diagnosis = repository.diagnose()
                            checking = false
                        }
                    },
                ) {
                    Text(if (checking) "Проверяю..." else "Проверить связь с ПК")
                }
                diagnosis?.let { report ->
                    Spacer(Modifier.height(8.dp))
                    Text(report, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { scope.launch { repository.clearUnpinned() } }) {
                Text("Очистить неотмеченную историю")
            }
        }
    }
}
