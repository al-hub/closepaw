package ai.closepaw.chatgpt.control

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AndroidAppCatalogTest {
    private val pm = mockk<PackageManager>()
    private fun app(pkg: String, label: String) = mockk<ResolveInfo>().also { info ->
        info.activityInfo = ActivityInfo().apply { packageName = pkg }
        every { info.loadLabel(pm) } returns label
    }
    @Test fun filtersPolicyBeforeLoadingLabelsAndDeduplicatesPackages() = runBlocking {
        val first = app("example.allowed", "Example")
        val duplicate = app("example.allowed", "Duplicate")
        val blocked = app("example.blocked", "Private")
        every { pm.queryIntentActivities(any(), PackageManager.MATCH_ALL) } returns listOf(first, duplicate, blocked)
        val apps = AndroidAppCatalog(pm) { it == "example.allowed" }.list("example")
        assertEquals(listOf("example.allowed"), apps.map { it.packageName })
        assertEquals("Example", apps.single().label)
        assertFalse(apps.single().isSystemApp)
        verify(exactly = 1) { first.loadLabel(pm) }
        verify(exactly = 0) { duplicate.loadLabel(pm) }
        verify(exactly = 0) { blocked.loadLabel(pm) }
    }
    @Test fun labelFailureStillPermitsExactPackageLookup() = runBlocking {
        val entry = app("example.allowed", "")
        every { entry.loadLabel(pm) } throws IllegalStateException("private label failure")
        every { pm.queryIntentActivities(any(), PackageManager.MATCH_ALL) } returns listOf(entry)
        assertEquals("example.allowed", AndroidAppCatalog(pm) { true }.list("allowed").single().label)
    }
    @Test fun boundedSortedResultsKeepDuplicateLabelsAsDistinctPackages() = runBlocking {
        val entries = (0..45).map { app("example.app${it.toString().padStart(2, '0')}", "Same") }
        every { pm.queryIntentActivities(any(), PackageManager.MATCH_ALL) } returns entries.reversed()
        val apps = AndroidAppCatalog(pm) { true }.list("same")
        assertEquals(40, apps.size)
        assertEquals("example.app00", apps.first().packageName)
        assertEquals("example.app39", apps.last().packageName)
    }
    @Test fun repeatedCatalogReadRechecksVisibilityButReusesRecentLabels() = runBlocking {
        val entry = app("example.allowed", "Example")
        every { pm.queryIntentActivities(any(), PackageManager.MATCH_ALL) } returns listOf(entry)
        val catalog = AndroidAppCatalog(pm) { true }
        assertEquals(1, catalog.list("example").size)
        assertEquals(1, catalog.list("Example").size)
        verify(exactly = 2) { pm.queryIntentActivities(any(), PackageManager.MATCH_ALL) }
        verify(exactly = 1) { entry.loadLabel(pm) }
        every { pm.queryIntentActivities(any(), PackageManager.MATCH_ALL) } returns emptyList()
        assertTrue(catalog.list("Example").isEmpty())
    }

}
