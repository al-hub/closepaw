package ai.closepaw.update

import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File
import kotlinx.serialization.json.*

/** Durable installation evidence, independent of MCP tasks and app process lifetime. */
internal class SelfUpdateLedger(file: File) {
    private val file = file
    @Synchronized fun read(): JsonObject = if (!file.exists()) buildJsonObject { put("state", "idle") }
        else Json.parseToJsonElement(file.readText()).jsonObject

    @Synchronized fun write(value: JsonObject) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized fun patch(vararg fields: Pair<String, JsonElement>) = write(JsonObject(read() + fields.toMap()))
}

internal object SelfUpdatePolicy {
    val activeStates = setOf("checking", "downloading", "installing", "user_action_required", "install_outcome_unknown")
    fun installed(record: JsonObject, version: String, code: Long): Boolean =
        record["target_version"]?.jsonPrimitive?.content == version &&
            record["target_code"]?.jsonPrimitive?.longOrNull == code

    fun verifyIdentity(packageName: String, expectedPackage: String, version: String?, expectedVersion: String,
        code: Long, installedCode: Long, signers: Set<String>, installedSigners: Set<String>) {
        require(packageName == expectedPackage) { "APK package mismatch." }
        require(version == expectedVersion) { "APK release version mismatch." }
        require(code > installedCode) { "APK is not a newer version." }
        require(signers.isNotEmpty() && signers == installedSigners) { "APK signing identity mismatch." }
    }
}
