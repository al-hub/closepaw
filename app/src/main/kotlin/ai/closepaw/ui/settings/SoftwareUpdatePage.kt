package ai.closepaw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.closepaw.BuildConfig
import ai.closepaw.ui.theme.PageMastheadDrillDown
import ai.closepaw.ui.theme.closePaw
import ai.closepaw.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val release: AppUpdater.Release) : UpdateUiState
    data class Downloading(val release: AppUpdater.Release) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
    data object InstallPermissionRequired : UpdateUiState
}

@Composable
internal fun SoftwareUpdatePage(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }

    fun checkForUpdate() {
        state = UpdateUiState.Checking
        scope.launch {
            state = when (val result = withContext(Dispatchers.IO) { AppUpdater.check() }) {
                AppUpdater.CheckResult.UpToDate -> UpdateUiState.UpToDate
                is AppUpdater.CheckResult.Available -> UpdateUiState.Available(result.release)
                is AppUpdater.CheckResult.Failed -> UpdateUiState.Error(result.message)
            }
        }
    }

    fun downloadAndInstall(release: AppUpdater.Release) {
        state = UpdateUiState.Downloading(release)
        scope.launch {
            val result = withContext(Dispatchers.IO) { AppUpdater.downloadAndVerify(context, release) }
            state = result.fold(
                onSuccess = { apk ->
                    if (AppUpdater.requestInstall(context, apk)) {
                        UpdateUiState.Available(release)
                    } else {
                        UpdateUiState.InstallPermissionRequired
                    }
                },
                onFailure = { UpdateUiState.Error(it.message ?: "Update download failed.") },
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PageMastheadDrillDown(title = "Software Update", onBack = onBack, onClose = onClose)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.closePaw.spacing.lg, vertical = MaterialTheme.closePaw.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.md),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(MaterialTheme.closePaw.spacing.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.sm),
                ) {
                    Text("ClosePaw", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Current version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    when (val current = state) {
                        UpdateUiState.Idle -> Text("Check GitHub Releases for a newer signed version.")
                        UpdateUiState.Checking -> ProgressLine("Checking for updates…")
                        UpdateUiState.UpToDate -> Text("ClosePaw is up to date.")
                        is UpdateUiState.Available -> {
                            Text("Version ${current.release.version} is available.")
                            Button(onClick = { downloadAndInstall(current.release) }) {
                                Text("Download and install")
                            }
                        }
                        is UpdateUiState.Downloading -> ProgressLine(
                            "Downloading version ${current.release.version} and verifying SHA-256…"
                        )
                        is UpdateUiState.Error -> {
                            Text(
                                "Update failed: ${current.message}",
                                color = MaterialTheme.colorScheme.error,
                            )
                            Button(onClick = { checkForUpdate() }) { Text("Retry") }
                        }
                        UpdateUiState.InstallPermissionRequired -> {
                            Text(
                                "Android opened the install-source permission. Allow ClosePaw to install unknown apps, then return here and check again."
                            )
                        }
                    }
                }
            }

            if (state is UpdateUiState.Idle || state is UpdateUiState.UpToDate || state is UpdateUiState.InstallPermissionRequired) {
                Button(onClick = { checkForUpdate() }) {
                    Text("Check for updates")
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(message: String) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(2.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
