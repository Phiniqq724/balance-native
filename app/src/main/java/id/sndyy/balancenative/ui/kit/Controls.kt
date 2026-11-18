package id.sndyy.balancenative.ui.kit

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.sndyy.balancenative.data.Format
import id.sndyy.balancenative.ui.theme.Ember
import id.sndyy.balancenative.ui.theme.Satoshi

/** Selected = bright cream on charcoal, like a lit key. Colors ease rather than snap. */
@Composable
fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onPress: () -> Unit) {
  val view = LocalView.current
  val bg by animateColorAsState(if (selected) Ember.text else Ember.raised, label = "chipBg")
  val fg by animateColorAsState(if (selected) Ember.bg else Ember.text, label = "chipFg")
  val source = remember { MutableInteractionSource() }
  val scale = rememberPressScale(source, 0.94f)
  Label(
    label,
    modifier
      .graphicsLayer { scaleX = scale; scaleY = scale }
      .clip(RoundedCornerShape(10.dp))
      .background(bg)
      .clickable(interactionSource = source, indication = null) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onPress()
      }
      .padding(horizontal = 14.dp, vertical = 8.dp),
    size = 14.sp,
    weight = FontWeight.Medium,
    color = fg,
    maxLines = 1,
  )
}

private val fieldText = TextStyle(fontFamily = Satoshi, fontSize = 16.sp, color = Ember.text)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
  focusedBorderColor = Ember.text,
  unfocusedBorderColor = Ember.line,
  cursorColor = Ember.accent,
  focusedLabelColor = Ember.muted,
  unfocusedLabelColor = Ember.muted,
  focusedContainerColor = Ember.bg,
  unfocusedContainerColor = Ember.bg,
)

@Composable
fun Field(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  keyboardType: KeyboardType = KeyboardType.Text,
  visualTransformation: VisualTransformation = VisualTransformation.None,
  singleLine: Boolean = true,
  textStyle: TextStyle = fieldText,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Label(label, size = 14.sp, tone = Tone.MUTED) },
    singleLine = singleLine,
    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    visualTransformation = visualTransformation,
    textStyle = textStyle,
    colors = fieldColors(),
    shape = RoundedCornerShape(14.dp),
    modifier = modifier,
  )
}

/** Shows typed digits grouped like the banks do ("25000" -> "25.000") while keeping the raw digits as the value. */
object RupiahGrouping : VisualTransformation {
  override fun filter(text: AnnotatedString): TransformedText {
    val digits = text.text
    if (digits.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
    val grouped = Format.digits(digits.toLongOrNull() ?: return TransformedText(text, OffsetMapping.Identity))
    return TransformedText(AnnotatedString(grouped), object : OffsetMapping {
      override fun originalToTransformed(offset: Int): Int {
        // A dot sits before digit i when (n - i) % 3 == 0; count those the cursor has passed.
        val n = digits.length
        val dots = (1 until offset.coerceAtMost(n)).count { (n - it) % 3 == 0 }
        return (offset + dots).coerceIn(0, grouped.length)
      }
      override fun transformedToOriginal(offset: Int): Int = grouped.take(offset).count(Char::isDigit).coerceIn(0, digits.length)
    })
  }
}

/** Keeps only digits, capped so the value stays a safe Long. */
fun amountInput(raw: String): String = raw.filter(Char::isDigit).trimStart('0').take(13)
