package ai.closepaw.chatgpt

import android.content.Context
import java.io.File

internal fun interface CloudflaredBinaryResolver {
    fun resolve(): File?
}

internal class AndroidCloudflaredBinaryResolver(
    private val context: Context,
) : CloudflaredBinaryResolver {
    override fun resolve(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: return null
        return File(nativeDir, "libcloudflared.so").takeIf { it.isFile && it.canExecute() }
    }
}
