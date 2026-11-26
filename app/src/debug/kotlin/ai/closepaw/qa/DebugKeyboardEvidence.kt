package ai.closepaw.qa

import ai.closepaw.chatgpt.control.ControlFrame
import ai.closepaw.chatgpt.control.DisplayObservationRecoveryPolicy
import ai.closepaw.chatgpt.control.DisplayWindowCensus
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * #147 aggregate diagnostics from the same GenericControl observation that
 * otherwise discloses only a bounded candidate list. No node IDs, coordinates,
 * screenshots, IME captions, editor text or URLs are persisted.
 * Diagnostic findings never authorize a native touch.
 */
internal object DebugKeyboardEvidence {
    private val allowedLabels = setOf("ㄱ", "ㅏ", "ㄴ", "Space")
    private fun safeReason(value: String?): String =
        value?.takeIf { it.length in 1..80 && it.matches(Regex("[a-z0-9_]+")) }
            ?: "unavailable"

    /** Persist only a fixed-shape native code, never arbitrary error text or image metadata. */
    internal fun nativeRefusalReason(data: JsonObject): String {
        val reason = data["reason_code"] as? JsonPrimitive
        return safeReason(reason?.takeIf { it.isString }?.content)
    }

    private val fixedDisplayRefusals = setOf(
        "display_platform_unsupported", "display_default_display_missing",
        "display_window_identity_mismatch", "display_ime_window_unverified",
        "display_window_multidisplay_or_pip", "display_window_ownership_policy_denied",
        "display_geometry_unverified", "display_unowned_window",
        "display_app_window_unverified", "display_touch_context_changed",
        "display_visible_region_empty", "display_surface_region_too_complex",
        "display_surface_isolation_failed",
    )

    internal fun safeDisplayRefusal(code: String?): String? = code?.takeIf { it in fixedDisplayRefusals }

    // One capture's bounded journal, never the process-wide diagnostic history.
    internal fun safeWindowChanges(journal: JsonObject): JsonObject = buildJsonObject {
        fun nonnegative(name: String, obj: JsonObject) =
            (obj[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
        nonnegative("elapsed_ms", journal)?.let { put("elapsed_ms", it) }
        nonnegative("unrecorded_count", journal)?.let { put("unrecorded_count", it) }
        put("events", JsonArray((journal["events"] as? JsonArray).orEmpty().take(16).mapNotNull { entry ->
            val event = entry as? JsonObject ?: return@mapNotNull null
            buildJsonObject {
                val allowed = mapOf(
                    "type" to setOf("windows_changed", "window_state_changed"),
                    "reported_owner" to setOf("chrome", "samsung_ime", "closepaw", "system", "other", "unknown"),
                    "event_window_match" to setOf("application", "ime", "other", "unknown"),
                    "event_display_class" to setOf("default", "other", "unknown"))
                for ((name, choices) in allowed) {
                    (event[name] as? JsonPrimitive)?.takeIf { it.isString && it.content in choices }
                        ?.let { put(name, it) }
                }
                nonnegative("after_action_source_check_ms", event)?.let { put("after_action_source_check_ms", it) }
                nonnegative("window_change_mask", event)?.takeIf { it <= 0x7ff }?.let { put("window_change_mask", it) }
                (event["unknown_change_bits"] as? JsonPrimitive)?.takeUnless { it.isString }
                    ?.booleanOrNull?.let { put("unknown_change_bits", it) }
            }
        }))
    }

    internal const val MAX_STRUCTURE_OBSERVATIONS = DisplayObservationRecoveryPolicy.MAX_READ_ONLY_RECOVERIES
    internal fun mayRetryStructure(reason: String, observations: Int, authority: Boolean, unknown: Boolean): Boolean =
        reason == "display_window_changed_during_capture" &&
            DisplayObservationRecoveryPolicy.mayRecoverPreExecution(
                reason = reason,
                recoveriesUsed = observations - 1,
                actionAttempted = false,
                outcomeUnknown = unknown,
                authorityActive = authority,
                maxRecoveries = MAX_STRUCTURE_OBSERVATIONS - 1,
                observationOnly = true,
            )

    internal fun safeDisplayWindowCensus(census: DisplayWindowCensus): JsonObject = buildJsonObject {
        put("total_windows", census.total)
        put("content_windows", census.content)
        put("empty_content", census.content == 0)
        put("duplicate_content_ids", census.duplicates)
        put("unowned_content_windows", census.unowned)
        put("rows_truncated", census.total > census.rows.take(DisplayWindowCensus.MAX_ROWS).size)
        put("windows", JsonArray(census.rows.take(DisplayWindowCensus.MAX_ROWS).map { row ->
            buildJsonObject {
                put("type", row.kind.name.lowercase())
                put("ownership", row.owner.name.lowercase())
                put("included_in_policy", row.included)
                put("layer_relative_to_ime", row.imeLayer.name.lowercase())
                row.overlapsImeBounds?.let { put("overlaps_ime_bounds", it) }
                put("active", row.active)
                put("focused", row.focused)
            }
        }))
    }

    fun fromObservation(data: JsonObject, succeeded: Boolean, wanted: List<String>,
        displayPlanRefusal: String? = null, census: DisplayWindowCensus? = null,
        windowChanges: JsonObject? = null): JsonObject {
        val labels = wanted.filter { it in allowedLabels }.distinct()
        val nodes = (data["nodes"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val scan = (data["keyboard"] as? JsonObject)?.get("scan") as? JsonObject
        val tracked = scan?.get("tracked_key_counts") as? JsonObject
        val matching = nodes.filter { node ->
            node["keyboard_key"]?.jsonPrimitive?.content?.let { it in labels } == true
        }
        val preflight = data["keyboard_preflight"] as? JsonObject
        return buildJsonObject {
            put("source", "single_observation_redacted")
            put("requested_keys", JsonArray(labels.map(::JsonPrimitive)))
            put("observation_succeeded", succeeded)
            if (!succeeded) {
                put("native_refusal_reason", nativeRefusalReason(data))
                safeDisplayRefusal(displayPlanRefusal)?.let { put("display_plan_refusal", it) }
                if (nativeRefusalReason(data) == "display_window_changed_during_capture" && windowChanges != null)
                    put("capture_window_changes", safeWindowChanges(windowChanges))
                if (displayPlanRefusal == "display_unowned_window" &&
                    nativeRefusalReason(data) == "display_surface_unverified" && census != null) {
                    put("display_window_census", safeDisplayWindowCensus(census))
                }
            }
            put("full_scan_available", scan != null)
            put("full_scan_visited", scan?.get("visited")?.jsonPrimitive?.intOrNull ?: -1)
            put("scan_list_limit", scan?.get("candidate_list_limit")?.jsonPrimitive?.intOrNull ?: -1)
            put("scan_list_omitted", scan?.get("candidate_list_omitted")?.jsonPrimitive?.intOrNull ?: -1)
            put("full_scan_key_counts", buildJsonObject {
                labels.forEach { label ->
                    (tracked?.get(label) as? JsonObject)?.let { put(label, it) }
                }
            })
            put("matched_full_frame_count", data["matched_node_count"]?.jsonPrimitive?.intOrNull ?: -1)
            put("returned_candidate_count", nodes.size)
            put("returned_native_label_matches", matching.size)
            put("returned_enabled_matches", matching.count {
                it["enabled"]?.jsonPrimitive?.booleanOrNull == true
            })
            put("returned_disabled_matches", matching.count {
                it["enabled"]?.jsonPrimitive?.booleanOrNull == false
            })
            put("returned_image_proposals", nodes.count {
                it["keyboard_image_candidate"]?.jsonPrimitive?.booleanOrNull == true
            })
            put("presentation_limited", data["output_truncated"]?.jsonPrimitive?.booleanOrNull == true)
            put("security_scan_complete", data["security_scan_complete"]?.jsonPrimitive?.booleanOrNull == true)
            put("visual_scope_proof_present", data["visual"] is JsonObject)
            put("pixel_role_certified", false)
            put("native_image_role_agreement", "not_independently_verified")
            put("native_touch_authorized_by_diagnostic", false)
            put("structural_preflight_status", safeReason(preflight?.get("status")?.jsonPrimitive?.content?.lowercase()))
            put("structural_preflight_reason", safeReason(preflight?.get("reason_code")?.jsonPrimitive?.content))
        }
    }

    internal data class FollowUp(val status: String, val evidence: JsonObject? = null, val reads: Int = 0)

    /**
     * Extracted coroutine boundary: the main QA state machine must not inline
     * diagnostics, and no native refusal or revoked consent starts an extra read.
     * Call ONLY for a successful observation with ambiguous candidate count.
     */
    suspend fun inspectAmbiguousCandidates(
        capture: suspend () -> ControlFrame,
        baseline: ControlFrame,
        keyIndex: Int,
        allowed: () -> Boolean,
    ): FollowUp {
        if (!allowed()) return FollowUp("skipped_authority_or_uncertainty")
        return try {
            val fresh = capture()
            if (!allowed()) return FollowUp("authority_revoked_during_follow_up", reads = 1)
            val editor = fresh.inputTarget
            FollowUp("captured_read_only", fromFollowUp(fresh,
                DebugOneStopQa.sameEditor(baseline, fresh),
                editor != null && DebugOneStopQa.SixKey.matches(
                    editor.text, editor.selectionStart, editor.selectionEnd, keyIndex)), 1)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            FollowUp("native_follow_up_failed")
        }
    }

    /** A separate passive capture after refusal is never the initial
     * observation, image re-certification, or retry of input. */
    fun fromFollowUp(frame: ControlFrame, editorSame: Boolean, prefixSame: Boolean): JsonObject =
        buildJsonObject {
            put("source", "fresh_native_read_only_after_failure")
            put("capture_refusal", safeReason(frame.reason))
            put("chrome_foreground", frame.packageName == "com.android.chrome")
            put("complete", !frame.truncated && !frame.navigationOnly)
            put("editor_identity_unchanged", editorSame)
            put("expected_prefix_unchanged", prefixSame)
            put("samsung_ime_present", frame.keyboard?.packageName == "com.samsung.android.honeyboard")
            put("ime_read_allowed", frame.keyboard?.readAllowed == true)
            put("ime_interact_allowed", frame.keyboard?.interactAllowed == true)
            put("scan_visited", frame.keyboardDiagnostics?.get("visited")?.jsonPrimitive?.intOrNull ?: -1)
            put("tracked_key_counts", frame.keyboardDiagnostics?.get("tracked_key_counts") ?: buildJsonObject { })
            put("image_or_pixel_recertified", false)
            put("touch_attempted", false)
        }
}
