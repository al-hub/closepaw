package ai.closepaw.ui.settings

import ai.closepaw.chatgpt.AndroidOpenAiTunnelBinaryResolver
import ai.closepaw.chatgpt.ChatGptMcpService
import ai.closepaw.chatgpt.SecureMcpTunnelConfigStore
import ai.closepaw.chatgpt.SecureMcpTunnelDiagnostics
import ai.closepaw.chatgpt.SecureMcpTunnelProbe
import ai.closepaw.ui.theme.closePaw
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ChatGptConnectionCard() {
    val context = LocalContext.current
    val state by ChatGptMcpService.connectionState.collectAsState()
    val scope = rememberCoroutineScope()
    val configStore = remember(context) { SecureMcpTunnelConfigStore(context.applicationContext) }
    var configuredTunnelId by remember {
        mutableStateOf(runCatching { configStore.load()?.tunnelId }.getOrNull())
    }
    var editingSecureTunnel by remember { mutableStateOf(false) }
    var tunnelIdInput by remember { mutableStateOf(configuredTunnelId.orEmpty()) }
    var runtimeKeyInput by remember { mutableStateOf("") }
    var configMessage by remember { mutableStateOf<String?>(null) }
    var secureTunnelProbe by remember { mutableStateOf<String?>(null) }
    var secureTunnelDiagnostics by remember { mutableStateOf<String?>(null) }

    SettingsCard {
        Text(
            text = "ChatGPT Voice",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = state.phase,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.transport?.let { transport ->
            Text(
                text = transport,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.publicMcpUrl?.let { url ->
            SelectionContainer {
                Text(
                    text = url,
                    style = MaterialTheme.closePaw.monoSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        state.tunnelId?.let { tunnelId ->
            SelectionContainer {
                Text(
                    text = tunnelId,
                    style = MaterialTheme.closePaw.monoSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        state.error?.let { error ->
            Text(
                text = "Tunnel: $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        configuredTunnelId?.let { tunnelId ->
            Text(
                text = "Secure tunnel configured: $tunnelId",
                style = MaterialTheme.closePaw.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        configMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (editingSecureTunnel) {
            OutlinedTextField(
                value = tunnelIdInput,
                onValueChange = { tunnelIdInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tunnel ID") },
                singleLine = true,
            )
            OutlinedTextField(
                value = runtimeKeyInput,
                onValueChange = { runtimeKeyInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Runtime API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = {
                    editingSecureTunnel = false
                    runtimeKeyInput = ""
                    configMessage = null
                }) {
                    Text("Cancel")
                }
                Button(onClick = {
                    runCatching { configStore.save(tunnelIdInput, runtimeKeyInput) }
                        .onSuccess {
                            configuredTunnelId = tunnelIdInput.trim()
                            runtimeKeyInput = ""
                            editingSecureTunnel = false
                            configMessage = "Saved securely. Stop and reconnect to switch transport."
                        }
                        .onFailure { error ->
                            configMessage = error.message ?: "Could not save secure tunnel settings."
                        }
                }) {
                    Text("Save secure tunnel")
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (configuredTunnelId != null) {
                    OutlinedButton(onClick = {
                        configStore.clear()
                        configuredTunnelId = null
                        tunnelIdInput = ""
                        runtimeKeyInput = ""
                        configMessage = "Secure tunnel config cleared. Reconnect to use Quick Tunnel."
                    }) {
                        Text("Clear Secure Tunnel")
                    }
                }
                OutlinedButton(onClick = {
                    tunnelIdInput = configuredTunnelId.orEmpty()
                    runtimeKeyInput = ""
                    configMessage = null
                    editingSecureTunnel = true
                }) {
                    Text(if (configuredTunnelId == null) "Configure Secure Tunnel" else "Replace Secure Tunnel")
                }
            }
        }

        secureTunnelProbe?.let { result ->
            Text(
                text = result,
                style = MaterialTheme.closePaw.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        secureTunnelDiagnostics?.let { result ->
            SelectionContainer {
                Text(
                    text = result,
                    style = MaterialTheme.closePaw.monoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(
                onClick = {
                    secureTunnelProbe = "Probing Android secure tunnel runtime…"
                    scope.launch {
                        val result = SecureMcpTunnelProbe(
                            AndroidOpenAiTunnelBinaryResolver(context)
                        ).run()
                        secureTunnelProbe = result.summary
                    }
                }
            ) {
                Text("Secure Tunnel Probe")
            }
            OutlinedButton(
                onClick = {
                    secureTunnelDiagnostics = "Running secure tunnel diagnostics…"
                    scope.launch {
                        secureTunnelDiagnostics = withContext(Dispatchers.IO) { SecureMcpTunnelDiagnostics().run().summary }
                    }
                }
            ) {
                Text("Tunnel Diagnostics")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.running) {
                OutlinedButton(onClick = { ChatGptMcpService.stop(context) }) {
                    Text("Stop")
                }
            } else {
                Button(onClick = { ChatGptMcpService.start(context) }) {
                    Text("Connect")
                }
            }
        }
    }
}
