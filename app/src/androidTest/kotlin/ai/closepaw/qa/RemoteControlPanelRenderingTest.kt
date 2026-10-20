package ai.closepaw.qa

import ai.closepaw.app.RemoteControlPanel
import ai.closepaw.chatgpt.control.ControlSession
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Offscreen native-View rendering, not a claim of real overlay routing or physical acceptance. */
@RunWith(AndroidJUnit4::class)
class RemoteControlPanelRenderingTest {
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun lightDarkAndLargeTextRenderWithLargeControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (night in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                val base = instrumentation.targetContext
                val configuration = Configuration(base.resources.configuration).apply {
                    fontScale = scale
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context = base.createConfigurationContext(configuration)
                val session = ControlSession(); session.start(); session.waitForUser()
                val panel = RemoteControlPanel(context, session, {}, { session.end() }, { _, _ -> })
                val density = context.resources.displayMetrics.density
                val width = (288 * density).toInt()
                panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((1600 * density).toInt(), View.MeasureSpec.AT_MOST))
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                assertTrue(panel.measuredHeight > 0)
                buttons(panel).filter { it.visibility == View.VISIBLE }.forEach {
                    assertTrue("Touch height for ${it.text}", it.height >= (56 * density).toInt())
                    assertTrue("Touch width for ${it.text}", it.width >= (48 * density).toInt())
                }
                val bitmap = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
                panel.draw(Canvas(bitmap))
                val folder = File(base.getExternalFilesDir(null), "premium-ux-qa").apply { mkdirs() }
                val renderFile = File(folder, "panel-${if (night) "dark" else "light"}-font-$scale.png")
                renderFile.outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
                // UTP uninstalls the target after execution. Export only synthetic render evidence
                // to a shell-owned public folder before that cleanup removes app-specific files.
                fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(command)).use { it.bufferedReader().readText() }
                val destination = "/sdcard/Download/closepaw-premium-ux-qa/${renderFile.name}"
                // UiAutomation executes argv directly: do not use shell && or wildcard expansion.
                shell("mkdir -p /sdcard/Download/closepaw-premium-ux-qa")
                shell("cp ${renderFile.absolutePath} $destination")
                assertTrue("Synthetic render exported before UTP cleanup",
                    (shell("stat -c %s $destination").trim().toLongOrNull() ?: 0L) > 0L)
            }
        }
    }
}
