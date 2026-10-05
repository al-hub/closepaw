package ai.closepaw.bridge

import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.platform.AppInfo
import ai.closepaw.platform.DisplayInfo
import ai.closepaw.platform.UIAction
import ai.closepaw.protocol.PlatformMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalAndroidExecutionAdapterTest {
    @Test
    fun intentAdapterLaunchesPackage() = runTest {
        val platform = FakePlatform()
        val adapter = AndroidIntentExecutionAdapter(platform)
        val result = adapter.execute(ExecutionRequest(ExecutionCapability.ANDROID_INTENT, "com.example.app"))
        assertEquals(0, result.exitCode)
        assertEquals("com.example.app", platform.launchedPackage)
    }

    @Test
    fun accessibilityAdapterExecutesTap() = runTest {
        val platform = FakePlatform()
        val adapter = AccessibilityExecutionAdapter(platform)
        val result = adapter.execute(
            ExecutionRequest(ExecutionCapability.ACCESSIBILITY, """{"action":"tap","x":12,"y":34}""")
        )
        assertEquals(0, result.exitCode)
        assertEquals(UIAction.TapAt(12, 34), platform.lastAction)
    }

    @Test
    fun adaptersRequirePlatformReadiness() = runTest {
        val platform = FakePlatform(ready = false)
        assertTrue(AndroidIntentExecutionAdapter(platform).probe() is AdapterAvailability.NeedsSetup)
        assertTrue(AccessibilityExecutionAdapter(platform).probe() is AdapterAvailability.NeedsSetup)
    }

    private class FakePlatform(private val ready: Boolean = true) : AndroidPlatform {
        override val mode = PlatformMode.ACCESSIBILITY
        var launchedPackage: String? = null
        var lastAction: UIAction? = null
        override suspend fun captureScreen() = ScreenSnapshot(System.currentTimeMillis(), emptyList(), null)
        override suspend fun performAction(action: UIAction): ActionResult {
            lastAction = action
            return ActionResult.Success("ok")
        }
        override fun hasRequiredPermissions() = ready
        override fun getCurrentPackageName(): String? = null
        override fun getDisplayInfo() = DisplayInfo(1080, 2400, 3f)
        override suspend fun getInstalledApps(): List<AppInfo> = emptyList()
        override suspend fun launchApp(packageName: String): ActionResult {
            launchedPackage = packageName
            return ActionResult.Success("launched")
        }
    }
}
