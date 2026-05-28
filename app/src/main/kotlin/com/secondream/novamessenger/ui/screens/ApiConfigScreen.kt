package com.secondream.novamessenger.ui.screens

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.secondream.novamessenger.R
import com.secondream.novamessenger.settings.AppSettings
import com.secondream.novamessenger.td.TdClient

@Composable
fun ApiConfigScreen() {
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // stringResource() can only be called from a composable scope, so we
    // capture the localised error strings here and reference them from the
    // Button onClick lambda below.
    val invalidValuesMsg = stringResource(R.string.api_invalid_values)
    val unknownErrorMsg = stringResource(R.string.login_unknown_error)

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
                text = "Nova",
                style = MaterialTheme.typography.displayLarge,
                fontStyle = FontStyle.Italic
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.api_config_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))

            OutlinedTextField(
                value = apiId,
                onValueChange = { apiId = it.filter { c -> c.isDigit() } },
                label = { Text(stringResource(R.string.api_id_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = apiHash,
                onValueChange = { apiHash = it.trim() },
                label = { Text(stringResource(R.string.api_hash_label)) },
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
                        error = invalidValuesMsg
                        return@Button
                    }
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            TdClient.configureApi(id, apiHash)
                        } catch (e: Throwable) {
                            error = e.message ?: unknownErrorMsg
                            loading = false
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (loading) R.string.login_step_configuring else R.string.action_next))
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
