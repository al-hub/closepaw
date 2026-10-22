package ai.closepaw.chatgpt

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Test

class GeminiUiMetadataTest {
    private val pkg = "com.google.android.apps.bard"
    private fun node(index: Int = 0) = GeminiUiNode(index, packageName = pkg,
        resourceId = "$pkg:id/assistant_robin_send_button", className = "android.widget.ImageButton",
        text = "private-question@example.com https://secret.invalid token=ABCD", description = "private-answer-account-123",
        clickable = true, clickAction = true)
    private val snapshot = GeminiUiSnapshot(true, "secret-target-key", 1, "secret composer", 1, true,
        submittedQuestion = "secret question", replies = listOf(GeminiReply("secret reply id", "secret answer")))
    private fun metadata(nodes: List<GeminiUiNode>, window: DraftWindowEvidence = DraftWindowEvidence(rootPackage = pkg)) =
        GeminiUiMetadata.build(nodes, window, snapshot)

    @Test fun metadataNeverContainsRawQuestionAnswerDescriptionOrTargetKey() {
        val node = node()
        val output = metadata(listOf(node)).toString()
        listOf(node.text, node.description, "secret-target-key", "secret composer", "secret question", "secret reply id", "secret answer", "secret.invalid").forEach {
            assertThat(output).doesNotContain(it)
        }
        val entry = metadata(listOf(node))["nodes"]!!.jsonArray.single().jsonObject
        assertThat(entry["text_length"]!!.jsonPrimitive.int).isEqualTo(node.text.length)
        assertThat(entry["description_length"]!!.jsonPrimitive.int).isEqualTo(node.description.length)
        assertThat(entry["resource_id"]!!.jsonPrimitive.content).isEqualTo(node.resourceId)
        assertThat(entry["class_name"]!!.jsonPrimitive.content).isEqualTo("android.widget.ImageButton")
        assertThat(entry["role"]!!.jsonPrimitive.content).isEqualTo("send")
    }
    @Test fun malformedAndUntrustedIdentifiersAreOmitted() {
        val bad = node().copy(resourceId = "https://secret.invalid/account?token=ABCD", className = "my.private.account.Token", packageName = pkg)
        val output = metadata(listOf(bad), DraftWindowEvidence(observedPackage = "secret.account@example.com", rootPackage = pkg, reason = "secret answer")).toString()
        listOf("secret.invalid", "ABCD", "my.private.account", "secret.account", "secret answer").forEach { assertThat(output).doesNotContain(it) }
        for (id in listOf("secret_token", "evil.package:id/token", "$pkg:id/name?secret", "$pkg:id/NameWithSecret", "$pkg:id/name\nsecret")) {
            assertThat(metadata(listOf(node().copy(resourceId = id)))["nodes"]!!.jsonArray.single().jsonObject.containsKey("resource_id")).isFalse()
        }
    }
    @Test fun nodeExportAndTextLengthsAreBounded() {
        val result = metadata((0..400).map { node(it).copy(parent = it - 1, text = "x".repeat(30000)) })
        assertThat(result["nodes"]!!.jsonArray.size).isAtMost(160)
        assertThat(result["nodes"]!!.jsonArray.size).isGreaterThan(0)
        assertThat(result["nodes_truncated"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(result["node_count"]!!.jsonPrimitive.int).isEqualTo(401)
        assertThat(result.toString().length).isLessThan(30000)
    }
    @Test fun unverifiedForegroundReturnsNoNodesOrRawWindowValues() {
        val result = GeminiUiMetadata.build(listOf(node()), DraftWindowEvidence(rootPackage = "evil.private.package"), snapshot.copy(foreground = false))
        assertThat(result["foreground"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(result["nodes"]!!.jsonArray).isEmpty()
        assertThat(result.toString()).doesNotContain("evil.private.package")
        assertThat(result["reason_code"]!!.jsonPrimitive.content).isEqualTo("target_not_verified")
    }
    @Test fun missingControlsAndIncompleteTraversalAreExplicit() {
        val noSend = GeminiUiMetadata.build(listOf(node().copy(resourceId = "", text = "", description = "")), DraftWindowEvidence(rootPackage = pkg), snapshot.copy(sendCandidates = 0, sendEnabled = false))
        assertThat(noSend["reason_code"]!!.jsonPrimitive.content).isEqualTo("send_control_missing")
        assertThat(noSend["send_candidates"]!!.jsonPrimitive.int).isEqualTo(0)
        val incomplete = GeminiUiMetadata.build(listOf(node()), DraftWindowEvidence(rootPackage = pkg), snapshot.copy(traversalComplete = false))
        assertThat(incomplete["traversal_complete"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(incomplete["reason_code"]!!.jsonPrimitive.content).isEqualTo("traversal_incomplete")
    }
    @Test fun hiddenHistoryCannotCrowdVisibleControlsOutOfDiagnostics() {
        val history = (0..200).map { node(it).copy(visible = false, resourceId = "", text = "", description = "") }
        val control = node(201).copy(description = "복사", resourceId = "", text = "")
        val result = metadata(history + control)
        assertThat(result.getValue("nodes").jsonArray.map { it.jsonObject.getValue("index").jsonPrimitive.int }).contains(201)
    }
}
