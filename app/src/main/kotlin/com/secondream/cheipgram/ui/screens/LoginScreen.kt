package com.secondream.cheipgram.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.secondream.cheipgram.R
import com.secondream.cheipgram.td.AuthState
import com.secondream.cheipgram.td.TdClient

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
            Text(
                text = "CheipGram",
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
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text(stringResource(R.string.login_phone_label)) },
                        placeholder = { Text("+39…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            if (phone.isBlank()) return@Button
                            busy = true; error = null
                            scope.launch {
                                runCatching { TdClient.setPhone(phone.trim()) }
                                    .onFailure { error = it.message }
                                busy = false
                            }
                        },
                        enabled = !busy && phone.length >= 6,
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
