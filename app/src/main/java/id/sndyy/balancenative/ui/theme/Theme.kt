package id.sndyy.balancenative.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.sndyy.balancenative.R

/** Ember: warm charcoal, orange only for spending heat, sage for money in, pink for errors. See DESIGN.md. */
object Ember {
  val bg = Color(0xFF1A1614)
  val surface = Color(0xFF221D1A)
  val raised = Color(0xFF2B2521)
  val line = Color(0xFF3A322C)
  val selected = Color(0xFF463C35)
  val text = Color(0xFFF3EBE2)
  val muted = Color(0xFFAC9F92)
  val accent = Color(0xFFFF7444)
  val income = Color(0xFF9BCB9F)
  val danger = Color(0xFFF07AAE)
  val heat = listOf(Color(0xFF2B2420), Color(0xFF5C2D1C), Color(0xFF93401F), Color(0xFFD2582B), Color(0xFFFF7444))

  const val HEX_TEXT = "#F3EBE2"
  const val HEX_MUTED = "#AC9F92"
  const val HEX_LINE = "#3A322C"
  const val HEX_ACCENT = "#FF7444"
  const val HEX_INCOME = "#9BCB9F"
}

object Space {
  val page = 20.dp
  val section = 32.dp
  /** Room under scrolling content so the floating bar never covers the last row. */
  val navClearance = 112.dp
}

val Satoshi = FontFamily(
  Font(R.font.satoshi_regular, FontWeight.Normal),
  Font(R.font.satoshi_medium, FontWeight.Medium),
  Font(R.font.satoshi_bold, FontWeight.Bold),
)

private fun style(size: Int, weight: FontWeight, line: Int = (size * 1.3f).toInt()) =
  TextStyle(fontFamily = Satoshi, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

private val typography = Typography(
  displayLarge = style(56, FontWeight.Bold, 60),
  displayMedium = style(46, FontWeight.Bold, 50),
  displaySmall = style(32, FontWeight.Bold, 38),
  headlineLarge = style(28, FontWeight.Bold),
  headlineMedium = style(24, FontWeight.Bold),
  headlineSmall = style(20, FontWeight.Bold),
  titleLarge = style(20, FontWeight.Bold),
  titleMedium = style(16, FontWeight.Bold),
  titleSmall = style(14, FontWeight.Bold),
  bodyLarge = style(16, FontWeight.Normal),
  bodyMedium = style(14, FontWeight.Normal),
  bodySmall = style(12, FontWeight.Normal),
  labelLarge = style(14, FontWeight.Bold),
  labelMedium = style(12, FontWeight.Medium),
  labelSmall = style(11, FontWeight.Medium),
)

/** Material 3, pinned to the palette instead of dynamic color. */
private val colors = darkColorScheme(
  primary = Ember.text,
  onPrimary = Ember.bg,
  primaryContainer = Ember.raised,
  onPrimaryContainer = Ember.text,
  secondary = Ember.muted,
  onSecondary = Ember.bg,
  secondaryContainer = Ember.selected,
  onSecondaryContainer = Ember.text,
  tertiary = Ember.accent,
  onTertiary = Ember.bg,
  background = Ember.bg,
  onBackground = Ember.text,
  surface = Ember.bg,
  onSurface = Ember.text,
  surfaceVariant = Ember.surface,
  onSurfaceVariant = Ember.muted,
  surfaceContainerLowest = Ember.bg,
  surfaceContainerLow = Ember.surface,
  surfaceContainer = Ember.surface,
  surfaceContainerHigh = Ember.raised,
  surfaceContainerHighest = Ember.raised,
  surfaceBright = Ember.selected,
  outline = Ember.muted,
  outlineVariant = Ember.line,
  error = Ember.danger,
  onError = Ember.bg,
  scrim = Color(0xCC0E0B0A),
)

@Composable
fun BalanceTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
