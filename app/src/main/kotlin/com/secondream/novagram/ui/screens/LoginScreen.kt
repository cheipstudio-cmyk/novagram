package com.secondream.novagram.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.secondream.novagram.R
import com.secondream.novagram.td.AuthState
import com.secondream.novagram.td.TdClient

@Composable
fun LoginScreen() {
    val state by TdClient.authState.collectAsState()
    val scope = rememberCoroutineScope()

    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
            // Theme-aware brand mark — use the lighter PNG against light
            // backgrounds, the darker one against dark, so the prism reads
            // well either way.
            val isLightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(
                    if (isLightTheme) com.secondream.novagram.R.drawable.ic_novagram_light
                    else com.secondream.novagram.R.drawable.ic_novagram_dark
                ),
                contentDescription = null,
                modifier = Modifier
                    .size(120.dp)
                    .padding(bottom = 12.dp)
            )
            Text(
                text = "Novagram",
                style = MaterialTheme.typography.displayLarge,
                fontStyle = FontStyle.Italic
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stepLabel(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))

            when (val s = state) {
                is AuthState.WaitPhoneNumber, AuthState.Initial, AuthState.WaitParameters -> {
                    var country by remember { mutableStateOf(com.secondream.novagram.util.Countries.DEFAULT) }
                    var showCountryPicker by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Country code picker button
                        Box(
                            modifier = Modifier
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { showCountryPicker = true }
                                .padding(horizontal = 14.dp, vertical = 16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(country.flag, style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    country.dialCode,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        // Number-only input
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { input -> phone = input.filter { it.isDigit() } },
                            label = { Text(stringResource(R.string.login_phone_only_number)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                        )
                    }
                    if (showCountryPicker) {
                        CountryPickerDialog(
                            onPick = { c ->
                                country = c
                                showCountryPicker = false
                            },
                            onDismiss = { showCountryPicker = false }
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            if (phone.isBlank()) return@Button
                            busy = true; error = null
                            val full = "${country.dialCode}${phone.trim()}"
                            scope.launch {
                                runCatching { TdClient.setPhone(full) }
                                    .onFailure { error = it.message }
                                busy = false
                            }
                        },
                        enabled = !busy && phone.length >= 5,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(
                                if (busy) R.string.login_step_sending else R.string.action_send_code
                            )
                        )
                    }
                }
                is AuthState.WaitCode -> {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.login_code_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            if (code.length < 4) return@Button
                            busy = true; error = null
                            scope.launch {
                                runCatching { TdClient.setCode(code) }
                                    .onFailure { error = it.message }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(
                                if (busy) R.string.login_step_verifying else R.string.action_continue
                            )
                        )
                    }
                }
                is AuthState.WaitPassword -> {
                    if (!s.hint.isNullOrBlank()) {
                        Text(
                            stringResource(R.string.login_password_hint, s.hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.login_password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            if (password.isBlank()) return@Button
                            busy = true; error = null
                            scope.launch {
                                runCatching { TdClient.setPassword(password) }
                                    .onFailure { error = it.message }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(
                                if (busy) R.string.login_step_verifying else R.string.action_unlock
                            )
                        )
                    }
                }
                AuthState.Ready -> {
                    Text(
                        stringResource(R.string.login_step_done),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is AuthState.Error -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                }
                else -> {
                    Text(
                        stringResource(R.string.login_step_loading),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun stepLabel(state: AuthState): String = when (state) {
    is AuthState.WaitPhoneNumber -> stringResource(R.string.login_step_phone)
    is AuthState.WaitCode -> stringResource(R.string.login_step_code)
    is AuthState.WaitPassword -> stringResource(R.string.login_step_password)
    is AuthState.Ready -> stringResource(R.string.login_step_ready)
    else -> stringResource(R.string.login_step_connecting)
}

@Composable
private fun CountryPickerDialog(
    onPick: (com.secondream.novagram.util.Country) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val countries = remember(query) {
        val q = query.trim()
        if (q.isBlank()) com.secondream.novagram.util.Countries.ALL
        else com.secondream.novagram.util.Countries.ALL.filter {
            it.name.contains(q, ignoreCase = true) || it.dialCode.contains(q) || it.iso.contains(q, ignoreCase = true)
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.login_country_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                ) {
                    items(countries, key = { it.iso }) { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(c) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(c.flag, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                c.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                c.dialCode,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
