package ai.closepaw.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import ai.closepaw.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object AppUpdater {
    private const val LATEST_RELEASE = "https://api.github.com/repos/al-hub/closepaw/releases/latest"
    private const val APK_NAME = "closepaw-aa-bridge.apk"
    private const val SHA_NAME = "closepaw-aa-bridge.apk.sha256"

    data class Release(val version: String, val apkUrl: String, val shaUrl: String)

    sealed interface CheckResult {
        data object UpToDate : CheckResult
        data class Available(val release: Release) : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    fun check(): CheckResult = try {
        val json = JSONObject(readText(LATEST_RELEASE))
        val version = json.getString("tag_name").removePrefix("v")
        if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) return CheckResult.UpToDate
        val assets = json.getJSONArray("assets")
        var apkUrl: String? = null
        var shaUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            when (asset.getString("name")) {
                APK_NAME -> apkUrl = asset.getString("browser_download_url")
                SHA_NAME -> shaUrl = asset.getString("browser_download_url")
            }
        }
        if (apkUrl == null || shaUrl == null) CheckResult.Failed("Release is missing the signed APK or checksum.")
        else CheckResult.Available(Release(version, apkUrl, shaUrl))
    } catch (e: Exception) {
        CheckResult.Failed(e.message ?: e.javaClass.simpleName)
    }

    fun downloadAndVerify(context: Context, release: Release): Result<File> = runCatching {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, APK_NAME)
        download(release.apkUrl, apk)
        val expected = readText(release.shaUrl).trim().substringBefore(' ').lowercase()
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid release checksum." }
        val actual = sha256(apk)
        require(actual == expected) { "Downloaded APK checksum mismatch." }
        apk
    }

    fun requestInstall(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    private fun readText(url: String): String {
        val connection = open(url)
        return connection.inputStream.bufferedReader().use { it.readText() }
            .also { connection.disconnect() }
    }

    private fun download(url: String, target: File) {
        val connection = open(url)
        connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        connection.disconnect()
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ClosePaw-AA-Bridge/${BuildConfig.VERSION_NAME}")
            val code = responseCode
            require(code in 200..299) { "Update server returned HTTP $code." }
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun compareVersions(a: String, b: String): Int {
        val aa = a.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val bb = b.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(aa.size, bb.size)) {
            val cmp = (aa.getOrElse(i) { 0 }).compareTo(bb.getOrElse(i) { 0 })
            if (cmp != 0) return cmp
        }
        return 0
    }
}
