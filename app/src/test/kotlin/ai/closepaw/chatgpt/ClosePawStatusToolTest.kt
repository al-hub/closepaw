package ai.closepaw.chatgpt

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ClosePawStatusToolTest {
    @Test fun statusDiscoversConfiguredTargetsFromRuntimeRegistry() {
        val snapshot = ClosePawStatusTool("test", 42).snapshot()
        val apps = snapshot["supported_read_apps"]!!.jsonArray
        assertThat(apps.map { it.jsonObject["id"]!!.jsonPrimitive.content })
            .containsExactlyElementsIn(BrowserReadTarget.supported.map { it.id }).inOrder()
        apps.forEach { app ->
            val value = app.jsonObject
            val target = BrowserReadTarget.from(value["id"]!!.jsonPrimitive.content)!!
            assertThat(value["package_name"]!!.jsonPrimitive.content).isEqualTo(target.packageName)
            assertThat(value.keys).containsExactly("id", "package_name", "display_name")
        }
        assertThat(snapshot["capability"]!!.jsonPrimitive.content).isEqualTo("status")
        assertThat(snapshot["version_name"]!!.jsonPrimitive.content).isEqualTo("test")
        assertThat(snapshot["version_code"]!!.jsonPrimitive.content).isEqualTo("42")
    }
}
