package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeControlSettingsTest {
    private fun config(access: String = "read", budget: Int = 4096) = buildJsonObject {
        put("node_budget", budget); put("output_limit", 50)
        put("apps", buildJsonObject { put("example.newapp", access) })
    }
    @Test fun grantsAreRuntimeScopedAndReadDoesNotPermitInteraction() {
        var disk: String? = null
        val store = RuntimeControlSettings({ disk }, { disk = it })
        assertTrue(store.access("example.newapp", false, "launch"))
        assertFalse(store.access("example.newapp", false, "read"))
        store.change(0, "apply", config())
        assertTrue(store.access("example.newapp", false, "read"))
        assertFalse(store.access("example.newapp", false, "interact"))
        assertTrue(RuntimeControlSettings({ disk }, { disk = it }).access("example.newapp", false, "read"))
        store.change(1, "apply", config("interact"))
        assertTrue(store.access("example.newapp", false, "interact"))
        store.change(2, "rollback", null)
        assertFalse(store.access("example.newapp", false, "interact"))
        assertEquals(3L, store.snapshot().revision)
    }
    @Test fun staleRevisionAndInvalidConfigDoNotChangeStoredState() {
        var disk: String? = null
        val store = RuntimeControlSettings({ disk }, { disk = it })
        store.change(0, "apply", config("deny")); val before = disk
        for (candidate in listOf(config("execute_code"), config(budget = 9000), config(budget = 1),
            JsonObject(config() + ("coordinates" to JsonPrimitive("1,2"))))) {
            assertThrows(IllegalArgumentException::class.java) { store.change(1, "apply", candidate) }
            assertEquals(before, disk)
        }
        assertThrows(IllegalArgumentException::class.java) { store.change(0, "apply", config()) }
        assertEquals(before, disk)
        assertFalse(store.access("example.newapp", true, "launch"))
    }
    @Test fun persistenceFailureNeverPublishesNewRevision() {
        val store = RuntimeControlSettings({ null }, { error("disk unavailable") })
        assertThrows(IllegalStateException::class.java) { store.change(0, "apply", config()) }
        assertEquals(0L, store.snapshot().revision)
    }

    @Test fun ordinaryAppReadDefaultsWithoutPerAppGrantButExplicitDenyWins() {
        var stored: String? = null
        val settings = RuntimeControlSettings({ stored }, { stored = it })
        // NORMAL is supplied by verified classifier, not inferred from a package name.
        assertTrue(settings.access("example.normal", true, "read"))
        assertTrue(settings.access("example.normal", true, "interact"))
        // Unknown/CAUTIOUS surfaces must not become readable via a generic policy.
        assertFalse(settings.access("example.unknown", false, "read"))
        assertFalse(settings.access("example.unknown", false, "interact"))
        val denied = buildJsonObject {
            put("node_budget", 4096); put("output_limit", 50)
            put("apps", buildJsonObject { put("example.normal", "deny") })
        }
        settings.change(0, "apply", denied)
        assertFalse(settings.access("example.normal", true, "read"))
        assertFalse(settings.access("example.normal", true, "interact"))
        assertFalse(settings.access("example.normal", true, "launch"))
    }
    @Test fun cautiousAppReadOnlyGrantDoesNotAuthorizeInteraction() {
        var stored: String? = null
        val settings = RuntimeControlSettings({ stored }, { stored = it })
        settings.change(0, "apply", config("read"))
        assertTrue(settings.access("example.newapp", false, "read"))
        assertFalse(settings.access("example.newapp", false, "interact"))
        assertFalse(settings.access("example.another", false, "read"))
    }
    @Test fun malformedAndOversizedSettingsAreRejected() {
        val invalid = buildJsonObject { put("node_budget", 4096); put("output_limit", 50)
            put("apps", buildJsonObject { put("not a package", "interact") }) }
        assertFalse(RuntimeControlSettings.validate(invalid))
        assertFalse(RuntimeControlSettings.validate(JsonObject(config() + ("output_limit" to JsonPrimitive(101)))))
        assertFalse(RuntimeControlSettings.validate(JsonObject(config() + ("node_budget" to JsonPrimitive("4096")))))
    }
}
