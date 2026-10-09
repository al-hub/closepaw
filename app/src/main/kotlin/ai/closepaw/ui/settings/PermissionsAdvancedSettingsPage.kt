package ai.closepaw.ui.settings

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.closepaw.BuildConfig
import ai.closepaw.chatgpt.BrowserReadDiagnostics
import ai.closepaw.update.AppUpdater
import ai.closepaw.ui.theme.Fleuron
import ai.closepaw.ui.theme.PageMastheadDrillDown
import ai.closepaw.ui.theme.closePaw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Composable
internal fun PermissionsAdvancedSettingsPage(
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    onAccessibilityClick: () -> Unit,
    onOverlayClick: () -> Unit,
    debugMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    traceEnabled: Boolean,
    onTraceEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PageMastheadDrillDown(title = "System & Debug", onBack = onBack, onClose = onClose)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.closePaw.spacing.lg)
        ) {
            SettingsSection(title = "Application") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ClosePaw ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
                    Text(
                        text = "Build type: ${BuildConfig.BUILD_TYPE} · Git: ${BuildConfig.GIT_SHA}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            SettingsSection(title = "Permissions") {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.md)) {
                    SettingsRow(
                        icon = Icons.Outlined.Settings,
                        title = "Accessibility Service",
                        isEnabled = isAccessibilityEnabled,
                        onClick = onAccessibilityClick
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Layers,
                        title = "Overlay Permission",
                        isEnabled = isOverlayEnabled,
                        onClick = onOverlayClick
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            SettingsSection(title = "Debug") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = MaterialTheme.closePaw.spacing.cardPadding, vertical = MaterialTheme.closePaw.spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BugReport,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Debug Mode",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Enable verbose logging",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = debugMode,
                            onCheckedChange = onDebugModeChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            UpdateSection()
            Spacer(modifier = Modifier.height(20.dp))
            DataStorageSection(
                traceEnabled = traceEnabled,
                onTraceEnabledChange = onTraceEnabledChange
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Fleuron()
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    SettingsSection(title = "Updates") {
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.md)) {
            Button(
                onClick = {
                    checking = true
                    message = null
                    scope.launch {
                        when (val result = withContext(Dispatchers.IO) { AppUpdater.check() }) {
                            AppUpdater.CheckResult.UpToDate -> {
                                release = null
                                message = "You are on the latest release."
                            }
                            is AppUpdater.CheckResult.Available -> {
                                release = result.release
                                message = "Version ${result.release.version} is available."
                            }
                            is AppUpdater.CheckResult.Failed -> {
                                release = null
                                message = "Update check failed: ${result.message}"
                            }
                        }
                        checking = false
                    }
                },
                enabled = !checking && !installing,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large
            ) {
                Text(if (checking) "Checking..." else "Check for Update")
            }

            release?.let { available ->
                Button(
                    onClick = {
                        installing = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                AppUpdater.downloadAndVerify(context.applicationContext, available)
                            }
                            result.onSuccess { apk ->
                                val launched = AppUpdater.requestInstall(context, apk)
                                message = if (launched) {
                                    "Update verified. Confirm installation in Android."
                                } else {
                                    "Allow ClosePaw to install unknown apps, then tap Download & Install again."
                                }
                            }.onFailure {
                                message = "Update failed: ${it.message ?: it.javaClass.simpleName}"
                            }
                            installing = false
                        }
                    },
                    enabled = !installing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(if (installing) "Downloading & Verifying..." else "Download & Install ${available.version}")
                }
            }

            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private const val TRACE_DIR = "inspection-trace"
private const val SESSIONS_DIR = "sessions"

private sealed interface ClearDataState {
    data object Idle : ClearDataState
    data object Clearing : ClearDataState
    data object Cleared : ClearDataState
    data class Failed(val message: String) : ClearDataState
}

@Composable
private fun DataStorageSection(
    traceEnabled: Boolean,
    onTraceEnabledChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var traceClearState by remember { mutableStateOf<ClearDataState>(ClearDataState.Idle) }
    var sessionClearState by remember { mutableStateOf<ClearDataState>(ClearDataState.Idle) }
    var showClearTracesConfirm by remember { mutableStateOf(false) }
    var showClearSessionsConfirm by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

    fun clearTraces() {
        if (traceClearState is ClearDataState.Clearing) return
        traceClearState = ClearDataState.Clearing
        scope.launch {
            traceClearState = withContext(Dispatchers.IO) {
                clearDirectory(context.getExternalFilesDir(TRACE_DIR), label = "traces")
            }
        }
    }

    fun clearSessions() {
        if (sessionClearState is ClearDataState.Clearing) return
        sessionClearState = ClearDataState.Clearing
        scope.launch {
            sessionClearState = withContext(Dispatchers.IO) {
                clearDirectory(File(context.filesDir, SESSIONS_DIR), label = "session history")
            }
        }
    }

    if (showClearTracesConfirm) {
        AlertDialog(
            onDismissRequest = { showClearTracesConfirm = false },
            title = { Text("Clear Traces") },
            text = { Text("All recorded execution traces will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearTracesConfirm = false
                        clearTraces()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearTracesConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showClearSessionsConfirm) {
        AlertDialog(
            onDismissRequest = { showClearSessionsConfirm = false },
            title = { Text("Clear Session History") },
            text = { Text("All saved sessions and chat history will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearSessionsConfirm = false
                        clearSessions()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearSessionsConfirm = false }) { Text("Cancel") }
            }
        )
    }

    SettingsSection(title = "Data & Storage") {
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.md)) {
            // Trace toggle
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(horizontal = MaterialTheme.closePaw.spacing.cardPadding, vertical = MaterialTheme.closePaw.spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Session Traces",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Save step-by-step session logs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = traceEnabled,
                            onCheckedChange = onTraceEnabledChange,
                            modifier = Modifier.testTag("qa-session-traces-switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                    if (traceEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "Traces may include screenshots and inputs. Turn off when done.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = MaterialTheme.closePaw.spacing.md, vertical = MaterialTheme.closePaw.spacing.sm)
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (!exporting) {
                        exporting = true
                        scope.launch {
                            exportMessage = withContext(Dispatchers.IO) { exportTracesToDownloads(context) }
                            exporting = false
                        }
                    }
                },
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large
            ) {
                Text(if (exporting) "Exporting..." else "Export Diagnostic Logs")
            }
            exportMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Clear traces button
            ClearDataButton(
                label = clearDataButtonLabel(traceClearState, idle = "Clear Traces", clearing = "Clearing Traces", cleared = "Traces Cleared"),
                enabled = traceClearState !is ClearDataState.Clearing && traceClearState !is ClearDataState.Cleared,
                onClick = { showClearTracesConfirm = true }
            )
            ClearFailureAlert(state = traceClearState, onRetry = { clearTraces() })

            // Clear session history button
            ClearDataButton(
                label = clearDataButtonLabel(sessionClearState, idle = "Clear Session History", clearing = "Clearing Sessions", cleared = "Sessions Cleared"),
                enabled = sessionClearState !is ClearDataState.Clearing && sessionClearState !is ClearDataState.Cleared,
                onClick = { showClearSessionsConfirm = true }
            )
            ClearFailureAlert(state = sessionClearState, onRetry = { clearSessions() })
        }
    }
}

private fun clearDirectory(directory: File?, label: String): ClearDataState {
    if (directory == null) {
        return ClearDataState.Failed("Could not access $label storage. Retry from Settings.")
    }
    return try {
        if (directory.deleteRecursively()) {
            ClearDataState.Cleared
        } else {
            ClearDataState.Failed("Could not clear $label. Retry from Settings.")
        }
    } catch (e: SecurityException) {
        ClearDataState.Failed("Could not clear $label because storage access was denied.")
    }
}

private fun clearDataButtonLabel(
    state: ClearDataState,
    idle: String,
    clearing: String,
    cleared: String,
): String = when (state) {
    ClearDataState.Idle,
    is ClearDataState.Failed -> idle
    ClearDataState.Clearing -> "$clearing..."
    ClearDataState.Cleared -> cleared
}

@Composable
private fun ClearFailureAlert(state: ClearDataState, onRetry: () -> Unit) {
    val failed = state as? ClearDataState.Failed ?: return
    SettingsAlertCard(
        message = failed.message,
        tone = AlertTone.Error,
        action = {
            TextButton(onClick = onRetry) {
                Text("Retry")
            }
        },
    )
}

@Composable
private fun ClearDataButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Icon(
            imageVector = Icons.Outlined.DeleteOutline,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}


private fun exportTracesToDownloads(context: android.content.Context): String {
    val source = context.getExternalFilesDir(TRACE_DIR)
    val hasTraces = source?.exists() == true && source.walkTopDown().any { it.isFile }
    val hasBrowserDiagnostics = BrowserReadDiagnostics.hasEvents()
    if (!hasTraces && !hasBrowserDiagnostics) {
        return "No diagnostic logs yet. Run a browser read or enable Session Traces first."
    }
    val name = "closepaw-diagnostics-" + System.currentTimeMillis() + ".zip"
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ClosePaw")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return "Could not create the diagnostic ZIP in Downloads."
        resolver.openOutputStream(uri)?.use { output ->
            ZipOutputStream(output).use { zip ->
                if (hasTraces && source != null) {
                    source.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relative = file.relativeTo(source).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(relative))
                        FileInputStream(file).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                zip.putNextEntry(ZipEntry("browser-read-diagnostics.json"))
                zip.write(BrowserReadDiagnostics.snapshot().toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        } ?: return "Could not open the diagnostic ZIP for writing."
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        "Saved to Downloads/ClosePaw/$name — attach this ZIP in ChatGPT."
    } catch (e: Exception) {
        "Diagnostic export failed: " + (e.message ?: e.javaClass.simpleName)
    }
}
