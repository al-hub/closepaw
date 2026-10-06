package ai.closepaw.ui.settings

import ai.closepaw.chatgpt.ChatGptMcpService
import ai.closepaw.ui.theme.closePaw
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun ChatGptConnectionCard() {
    val context = LocalContext.current
    val state by ChatGptMcpService.connectionState.collectAsState()

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
        state.publicMcpUrl?.let { url ->
            SelectionContainer {
                Text(
                    text = url,
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
