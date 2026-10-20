package ai.closepaw.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class CalmThemeContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val l1 = a.luminance(); val l2 = b.luminance()
        return (maxOf(l1, l2) + 0.05f) / (minOf(l1, l2) + 0.05f)
    }
    @Test fun normalTextOnMaterialRolesMeetsAAInBothThemes() {
        for (scheme in listOf(ClosePawLightColorScheme, ClosePawDarkColorScheme)) {
            for ((text, background) in listOf(
                scheme.onPrimary to scheme.primary,
                scheme.onSecondary to scheme.secondary,
                scheme.onTertiary to scheme.tertiary,
                scheme.onError to scheme.error,
                scheme.onSurface to scheme.surface,
                scheme.onSurfaceVariant to scheme.surfaceVariant,
                scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onTertiaryContainer to scheme.tertiaryContainer,
                scheme.onErrorContainer to scheme.errorContainer,
            )) assertTrue("Normal text contrast ${contrast(text, background)}", contrast(text, background) >= 4.5f)
        }
    }
}
