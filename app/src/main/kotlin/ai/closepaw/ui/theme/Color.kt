package ai.closepaw.ui.theme

import androidx.compose.ui.graphics.Color

// Calm Intelligence palette. Legacy token names remain stable for existing components.

// Light — cool porcelain with readable blue/cyan accents.
val Paper = Color(0xFFF3F7FC)
val PaperInset = Color(0xFFE6EEF7)
val Ink = Color(0xFF12243A)
val InkMuted = Color(0xFF4D6077)
val InkFaint = Color(0xFF596C82)
val Claw = Color(0xFF135FC0)
val Moss = Color(0xFF006D7A)
val Amber = Color(0xFFA06000)
val Rust = Color(0xFFB33145)
val Hairline = Ink.copy(alpha = 0.12f)
val InkGhost = Ink.copy(alpha = 0.08f)

// Material containers share the same calm palette across cards, chips and panels.
val PrimaryContainerLight = Color(0xFFD6E7FF)   // Claw-tinted Paper
val TertiaryContainerLight = Color(0xFFFAE2BD)  // Amber-tinted Paper
val ErrorContainerLight = Color(0xFFFFD9DF)     // Rust-tinted Paper

// Dark — deep navy with soft, high-contrast light.
val PaperDark = Color(0xFF09121F)
val PaperInsetDark = Color(0xFF122236)
val InkDark = Color(0xFFEDF4FF)
val InkMutedDark = Color(0xFFB5C7DE)
val InkFaintDark = Color(0xFF95ABC6)
val ClawDark = Color(0xFF82BCFF)
val MossDark = Color(0xFF73D4DD)
val AmberDark = Color(0xFFF2C477)
val RustDark = Color(0xFFFF9DAA)
val HairlineDark = InkDark.copy(alpha = 0.12f)
val InkGhostDark = InkDark.copy(alpha = 0.08f)

val PrimaryContainerDark = Color(0xFF183A61)
val TertiaryContainerDark = Color(0xFF46341C)
val ErrorContainerDark = Color(0xFF4B2330)
