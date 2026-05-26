package com.secondream.cheipgram.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.secondream.cheipgram.settings.AppSettings
import com.secondream.cheipgram.td.TdClient

@Composable
fun ApiConfigScreen() {
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "CheipGram",
                style = MaterialTheme.typography.displayLarge,
                fontStyle = FontStyle.Italic
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Per iniziare ti servono le credenziali API. Vai su my.telegram.org, accedi, crea un'applicazione e copia qui sotto api_id e api_hash. Restano sul tuo dispositivo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))

            OutlinedTextField(
                value = apiId,
                onValueChange = { apiId = it.filter { c -> c.isDigit() } },
                label = { Text("api_id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = apiHash,
                onValueChange = { apiHash = it.trim() },
                label = { Text("api_hash") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
            }

            Button(
                onClick = {
                    val id = apiId.toIntOrNull()
                    if (id == null || id <= 0 || apiHash.length < 8) {
                        error = "Controlla i valori inseriti."
                        return@Button
                    }
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            TdClient.configureApi(id, apiHash)
                        } catch (e: Throwable) {
                            error = e.message ?: "Errore sconosciuto"
                            loading = false
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (loading) "Configurazione…" else "Avanti")
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
