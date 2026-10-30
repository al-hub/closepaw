package ai.closepaw.chatgpt.control

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes only the bounded projection-specific snapshot. Callers own the ZIP stream. */
internal object ProjectionDiagnosticZip {
    const val ENTRY_NAME = "projection-frame-diagnostics.json"

    fun write(zip: ZipOutputStream, snapshotJson: String = ControlDiagnostics.projectionExportJson()) {
        zip.putNextEntry(ZipEntry(ENTRY_NAME))
        zip.write(snapshotJson.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
