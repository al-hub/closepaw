package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.operations.operationHash
import ai.closepaw.util.recycleCompat
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.serialization.json.*

internal data class NativeControlWindows(val selection: ControlWindowSelection, val signature: String, val diagnostics: JsonObject)

/** Metadata only; no retained native refs, titles, account identifiers or screen text. */
internal fun controlWindows(foreground: Int, pkg: String, windows: List<AccessibilityWindowInfo>): NativeControlWindows {
    val projected = windows.take(65).map { window ->
        val root = if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) window.root else null
        val rootPackage = try { root?.packageName?.toString().orEmpty() } finally { root?.recycleCompat() }
        val parent = window.parent
        val parentId = try { parent?.id } finally { parent?.recycle() }
        val anchor = window.anchor
        val anchorId = try { anchor?.windowId } finally { anchor?.recycleCompat() }
        ControlWindow(window.id, window.displayId, window.layer, window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
            window.isActive, window.isFocused, rootPackage, root != null, parentId, anchorId, window.isInPictureInPictureMode)
    }
    val selected = ControlWindowPolicy.select(foreground, pkg, projected)
    val geometry = windows.take(65).sortedBy { it.id }.joinToString("\n") {
        val rect = Rect(); it.getBoundsInScreen(rect)
        "${it.id}:${it.type}:${rect.flattenToString()}"
    }
    val signature = operationHash(projected.sortedBy { it.id }.joinToString("\n") + "\n" + geometry)
    val diagnostics = buildJsonObject {
        put("window_count", windows.size); put("foreground_window_id", foreground)
        put("selected_window_id", selected.target); put("owned_window_count", selected.members.size)
        put("truncated", windows.size > 16)
        selected.reason?.let { put("selection_reason", it) }
        put("windows", JsonArray(projected.take(16).map { item -> buildJsonObject {
            put("id", item.id); put("display_id", item.display); put("layer", item.layer)
            put("application", item.application); put("active", item.active); put("focused", item.focused)
            put("root_available", item.hasRoot); put("same_package", item.packageName == pkg)
            item.parent?.let { put("parent_id", it) }; item.anchor?.let { put("anchor_window_id", it) }
        } }))
    }
    return NativeControlWindows(selected, signature, diagnostics)
}
