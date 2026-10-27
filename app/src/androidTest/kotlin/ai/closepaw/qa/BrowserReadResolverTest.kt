package ai.closepaw.qa

import ai.closepaw.chatgpt.control.BrowserReadResolver
import android.content.Intent
import android.content.IntentFilter
import android.content.UriRelativeFilterGroup
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.PatternMatcher
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android IntentFilter/ResolveInfo adapter regression, no browser/app permissions changed. */
@RunWith(AndroidJUnit4::class)
class BrowserReadResolverTest {
    private val pkg = "example.browser"
    private fun result(scheme: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg; name = "$pkg.BrowserActivity"; enabled = true; exported = true
            applicationInfo = ApplicationInfo().apply { enabled = true }
        }
        filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT); addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme(scheme)
        }
    }
    private fun admitted(change: (ResolveInfo, String) -> Unit = { _, _ -> }) =
        BrowserReadResolver.permits(pkg) { intent, flags ->
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertEquals(PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER, flags)
            result(intent.scheme!!).also { change(it, intent.scheme!!) }
        }

    @Test fun requiresCurrentSameComponentAndMatchedGeneralFilterForBothSchemes() {
        assertTrue(admitted())
        assertFalse(admitted { value, scheme -> if (scheme == "https") value.activityInfo.packageName = "other.browser" })
        assertFalse(admitted { value, scheme -> if (scheme == "https") value.activityInfo.name = "$pkg.OtherActivity" })
        assertFalse(BrowserReadResolver.permits(pkg) { intent, _ -> if (intent.scheme == "http") result("http") else null })
        assertFalse(admitted { value, _ -> value.filter = IntentFilter(Intent.ACTION_VIEW) })
        assertFalse(admitted { value, _ -> value.filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT); addCategory(Intent.CATEGORY_BROWSABLE); addDataScheme("ftp")
        } })
        assertFalse(admitted { value, _ -> value.filter.removeCategory(Intent.CATEGORY_DEFAULT) })
        assertFalse(admitted { value, _ -> value.filter.removeCategory(Intent.CATEGORY_BROWSABLE) })
    }
    @Test fun rejectsHostPathSchemeSpecificAndMimeScopedLinkHandlers() {
        assertFalse(admitted { value, _ -> value.filter.addDataAuthority("example.invalid", null) })
        assertFalse(admitted { value, _ -> value.filter.addDataPath("/", PatternMatcher.PATTERN_PREFIX) })
        assertFalse(admitted { value, _ -> value.filter.addDataSchemeSpecificPart("//example.invalid/", PatternMatcher.PATTERN_LITERAL) })
        assertFalse(admitted { value, _ -> value.filter.addDataType("text/html") })
        if (Build.VERSION.SDK_INT >= 35) {
            assertFalse(admitted { value, _ -> value.filter.addUriRelativeFilterGroup(UriRelativeFilterGroup(true)) })
        }
    }
    @Test fun rejectsChooserMissingEvidenceDisabledAndUnexportedResults() {
        assertFalse(admitted { value, _ -> value.activityInfo.packageName = "android"; value.activityInfo.name = "com.android.internal.app.ResolverActivity" })
        assertFalse(admitted { value, _ -> value.filter = null })
        assertFalse(admitted { value, _ -> value.activityInfo = null })
        assertFalse(admitted { value, _ -> value.activityInfo.enabled = false })
        assertFalse(admitted { value, _ -> value.activityInfo.exported = false })
        assertFalse(admitted { value, _ -> value.activityInfo.applicationInfo.enabled = false })
        assertFalse(admitted { value, _ -> value.activityInfo.applicationInfo = null })
        assertFalse(admitted { value, _ -> value.activityInfo.name = "" })
        assertFalse(BrowserReadResolver.permits(pkg) { _, _ -> throw SecurityException("unavailable") })
    }
}
