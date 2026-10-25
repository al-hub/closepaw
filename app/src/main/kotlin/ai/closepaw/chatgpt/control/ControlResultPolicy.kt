package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.*

/** A native acknowledgement is never evidence of a visible postcondition. No effect is replayed. */
internal object ControlResultPolicy {
    data class Verdict(val confirmed: Boolean, val reason: String, val goalVerified: Boolean = false)
    fun verify(action: String, before: ControlFrame, after: ControlFrame?, node: ControlNode?, input: JsonObject): Verdict {
        if (after == null || after.reason != null) return Verdict(false, "post_observation_unavailable")
        if (action == "open_app") return Verdict(after.matchesApplication(input["package_name"]?.jsonPrimitive?.content),
            "target_app_not_observed", true)
        if (after.truncated && action != "home") return Verdict(false, "post_observation_incomplete")
        val matching = after.nodes.filter { it.resourceId == node?.resourceId && it.bounds == node?.bounds &&
            it.className == node?.className }
        val target = matching.singleOrNull()
        if (action == "focus") {
            // IME opening may resize/move the field. Only a unique nonempty semantic identity
            // can survive that geometry change; never choose one of duplicate resource IDs.
            val uniqueBefore = node != null && node.resourceId.isNotEmpty() && before.nodes.count {
                it.resourceId == node.resourceId && it.className == node.className
            } == 1
            val semantic = if (uniqueBefore) after.nodes.filter {
                it.resourceId == node?.resourceId && it.className == node?.className
            }.singleOrNull() else target
            return Verdict(after.packageName == before.packageName && after.windowId == before.windowId &&
                semantic?.focused == true, "focus_unconfirmed", true)
        }
        if (action == "input" && node != null) {
            val plan = UnifiedInputPolicy.plan(node, input["text"]?.jsonPrimitive?.content.orEmpty(),
                input["method"]?.jsonPrimitive?.content ?: "auto", input["mode"]?.jsonPrimitive?.content ?: "empty",
                input["clipboard_consent"]?.jsonPrimitive?.booleanOrNull == true)
            return Verdict(plan.refusal == null && after.packageName == before.packageName && after.windowId == before.windowId &&
                target?.text == plan.resultText, "input_unconfirmed", true)
        }
        if (action == "type") return Verdict(after.packageName == before.packageName && target?.text == input["text"]?.jsonPrimitive?.content, "input_unconfirmed", true)
        if (action in setOf("click", "tap") && node?.checkable == true) return Verdict(
            after.packageName == before.packageName && target?.checkable == true && target.checked != node.checked,
            "checked_state_unconfirmed", true)
        val changed = before.packageName != after.packageName || before.windowId != after.windowId || before.fingerprint != after.fingerprint
        return Verdict(changed, "no_visible_change")
    }
}

/** Bounded exact identities survive unrelated searches; native launch policy is always rechecked. */
internal class ControlAppSelections {
    private val packages = linkedSetOf<String>()
    fun add(values: List<String>) { packages.addAll(values); while (packages.size > 256) packages.remove(packages.first()) }
    fun clear() = packages.clear()
    fun refusal(pkg: String, launchAllowed: Boolean): String? = when {
        !launchAllowed -> "app_launch_blocked"
        pkg !in packages -> "app_selection_required"
        else -> null
    }
}
