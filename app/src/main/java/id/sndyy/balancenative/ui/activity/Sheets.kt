package id.sndyy.balancenative.ui.activity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import id.sndyy.balancenative.balance
import id.sndyy.balancenative.core.AccountState
import id.sndyy.balancenative.data.AiKind
import id.sndyy.balancenative.data.Category
import id.sndyy.balancenative.data.Direction
import id.sndyy.balancenative.data.Format
import id.sndyy.balancenative.data.InstalledApp
import id.sndyy.balancenative.data.ReviewState
import id.sndyy.balancenative.data.Transaction
import id.sndyy.balancenative.data.Unparsed
import id.sndyy.balancenative.ui.kit.Chip
import id.sndyy.balancenative.ui.kit.EmberSwitch
import id.sndyy.balancenative.ui.kit.Field
import id.sndyy.balancenative.ui.kit.Label
import id.sndyy.balancenative.ui.kit.Money
import id.sndyy.balancenative.ui.kit.Rule
import id.sndyy.balancenative.ui.kit.RupiahGrouping
import id.sndyy.balancenative.ui.kit.Tone
import id.sndyy.balancenative.ui.kit.amountInput
import id.sndyy.balancenative.ui.theme.Ember
import id.sndyy.balancenative.ui.theme.Satoshi
import id.sndyy.balancenative.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun primaryButtonColors() = ButtonDefaults.buttonColors(containerColor = Ember.text, contentColor = Ember.bg)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Ember.surface,
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
  ) {
    Column(
      Modifier.fillMaxWidth().navigationBarsPadding().padding(start = Space.page, end = Space.page, top = 4.dp, bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp),
    ) { content() }
  }
}

/** Details for one transaction, plus the two things worth fixing by hand: category and transfer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TxSheet(tx: Transaction, categories: List<Category>, label: (String) -> String, title: (Transaction) -> String, onClose: () -> Unit) {
  val core = LocalContext.current.balance.core
  val scope = rememberCoroutineScope()
  var categoryId by remember(tx.id) { mutableStateOf(tx.categoryId) }
  var isTransfer by remember(tx.id) { mutableStateOf(tx.isTransfer) }
  var error by remember { mutableStateOf<String?>(null) }

  Sheet(onClose) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Label(title(tx), size = 15.sp, weight = FontWeight.Medium, tone = Tone.MUTED, maxLines = 2)
      Money(tx.amount, size = 38.sp, sign = if (tx.direction == Direction.IN) "+" else "", color = if (tx.direction == Direction.IN) Ember.income else Ember.text)
      Label("${label(tx.source)}  ·  ${Format.shortDate(Format.date(tx.occurredAt))}, ${Format.time(tx.occurredAt)}", size = 13.sp, tone = Tone.MUTED)
    }
    if (tx.rawBody.isNotEmpty()) {
      Label(tx.rawBody, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ember.bg).padding(12.dp), size = 13.sp, tone = Tone.MUTED)
    }
    Rule()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Label("Category", size = 13.sp, weight = FontWeight.Medium, tone = Tone.MUTED)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (categories.map { it.id to it.name } + (null to "None")).forEach { (id, name) ->
          Chip(name, categoryId == id) {
            val previous = categoryId
            categoryId = id
            scope.launch { runCatching { core.setCategory(tx.id, id) }.onFailure { categoryId = previous; error = "Couldn't save the category." } }
          }
        }
      }
    }
    Rule()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label("Between my own accounts", weight = FontWeight.Medium)
        Label("Kept out of spending and income.", size = 13.sp, tone = Tone.MUTED)
      }
      EmberSwitch(
        checked = isTransfer,
        onCheckedChange = { value ->
          isTransfer = value
          scope.launch { runCatching { core.setTransfer(tx.id, value) }.onFailure { isTransfer = !value; error = "Couldn't save that change." } }
        },
      )
    }
    error?.let { Label(it, size = 14.sp, tone = Tone.DANGER) }
    if (tx.isManual) {
      TextButton(onClick = { scope.launch { runCatching { core.deleteTransaction(tx.id) }.fold({ onClose() }, { error = "Couldn't delete." }) } }) {
        Label("Delete", weight = FontWeight.Medium, tone = Tone.DANGER)
      }
    }
  }
}

private data class Verdict(val kind: AiKind, val direction: Direction, val amount: Long, val counterparty: String?)

private val NOT_A_TRANSACTION = Verdict(AiKind.AD_OR_OTHER, Direction.OUT, 0, null)

/** "Spent Rp 25.000 at X?" for what the model read. */
private fun question(v: Verdict): String {
  val amount = Format.rupiah(v.amount)
  return when (v.kind) {
    AiKind.EXPENSE -> if (v.counterparty != null) "Spent $amount at ${v.counterparty}?" else "Spent $amount?"
    AiKind.INCOME -> if (v.counterparty != null) "Got $amount from ${v.counterparty}?" else "Got $amount?"
    AiKind.TRANSFER -> "Moved $amount between your accounts?"
    AiKind.AD_OR_OTHER -> "Not a transaction?"
  }
}

private fun verdictOf(n: Unparsed): Verdict? {
  val r = n.review ?: return null
  if (r.state != ReviewState.SUGGESTED || r.kind == null || r.direction == null || r.amount == null) return null
  return Verdict(r.kind, r.direction, r.amount, r.counterparty)
}

/** A notification no rule understood, with the AI's reading and the ways to answer it. */
@Composable
fun UnparsedCard(n: Unparsed, app: InstalledApp?, label: String, fixing: Boolean, modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (fixing) Ember.raised else Ember.surface).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      AppIcon(app, label)
      Label(label, Modifier.weight(1f), size = 13.sp, weight = FontWeight.Medium, tone = Tone.MUTED, maxLines = 1)
      Label("${Format.dayLabel(Format.date(n.postedAt))}, ${Format.time(n.postedAt)}", size = 13.sp, tone = Tone.MUTED)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      n.title?.let { Label(it, weight = FontWeight.Bold) }
      Label(n.body, size = 14.sp, tone = Tone.MUTED, maxLines = 4)
    }
    ReviewActions(n, fixing)
  }
}

@Composable
private fun ReviewActions(n: Unparsed, openFix: Boolean) {
  val context = LocalContext.current
  val view = LocalView.current
  val core = context.balance.core
  val account = context.balance.account
  val scope = rememberCoroutineScope()
  var fixing by remember(n.id) { mutableStateOf(openFix) }
  var busy by remember(n.id) { mutableStateOf(false) }
  var error by remember(n.id) { mutableStateOf<String?>(null) }
  val suggestion = verdictOf(n)

  fun send(v: Verdict) {
    if (busy) return
    busy = true
    error = null
    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    scope.launch {
      try {
        core.resolve(n.id, v.kind, v.direction, v.amount, v.counterparty)
        // Best effort: a failed upload only means the cloud model learns less.
        if (account.state.value is AccountState.Signed) {
          account.shareExample(listOfNotNull(n.title, n.body).joinToString(" - "), v.kind.value, v.direction.value, v.amount, v.counterparty?.trim()?.ifEmpty { null })
        }
      } catch (e: Exception) {
        error = "Couldn't save. Try again."
        busy = false
      }
    }
  }

  AnimatedContent(n.review?.state == ReviewState.PENDING, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "review") { pending ->
    if (pending) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), color = Ember.muted, strokeWidth = 2.dp)
        Label("Reading…", size = 14.sp, tone = Tone.MUTED)
      }
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (suggestion != null) Label(question(suggestion), weight = FontWeight.Medium) else Label("Couldn't read this one.", size = 14.sp, tone = Tone.MUTED)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          if (suggestion != null) {
            Button(onClick = { send(suggestion) }, enabled = !busy, colors = primaryButtonColors()) { Label("Yes", weight = FontWeight.Medium, color = Ember.bg) }
          }
          TextButton(onClick = { fixing = true }, enabled = !busy) { Label(if (suggestion != null) "Fix" else "Add", weight = FontWeight.Medium) }
          if (suggestion?.kind != AiKind.AD_OR_OTHER) {
            TextButton(onClick = { send(NOT_A_TRANSACTION) }, enabled = !busy) { Label("Not money", weight = FontWeight.Medium, tone = Tone.MUTED) }
          }
        }
        error?.let { Label(it, size = 14.sp, tone = Tone.DANGER) }
      }
    }
  }

  if (fixing) {
    FixSheet(suggestion?.takeIf { it.kind != AiKind.AD_OR_OTHER }, onClose = { fixing = false }) { v ->
      fixing = false
      send(v)
    }
  }
}

@Composable
private fun FixSheet(start: Verdict?, onClose: () -> Unit, onSave: (Verdict) -> Unit) {
  var kind by remember { mutableStateOf(start?.kind ?: AiKind.EXPENSE) }
  var direction by remember { mutableStateOf(start?.direction ?: Direction.OUT) }
  var amount by remember { mutableStateOf(start?.amount?.takeIf { it > 0 }?.toString().orEmpty()) }
  var name by remember { mutableStateOf(start?.counterparty.orEmpty()) }
  var error by remember { mutableStateOf<String?>(null) }

  Sheet(onClose) {
    Label("What was it?", size = 22.sp, weight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      listOf(AiKind.EXPENSE to "Spent", AiKind.INCOME to "Received", AiKind.TRANSFER to "Own accounts").forEach { (k, l) ->
        Chip(l, kind == k) {
          kind = k
          if (k == AiKind.EXPENSE) direction = Direction.OUT
          if (k == AiKind.INCOME) direction = Direction.IN
        }
      }
    }
    AnimatedVisibility(kind == AiKind.TRANSFER) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Out", direction == Direction.OUT) { direction = Direction.OUT }
        Chip("In", direction == Direction.IN) { direction = Direction.IN }
      }
    }
    Field(amount, { amount = amountInput(it) }, "Amount (Rp)", Modifier.fillMaxWidth(), KeyboardType.Number, RupiahGrouping)
    Field(name, { name = it }, "Who", Modifier.fillMaxWidth())
    error?.let { Label(it, size = 14.sp, tone = Tone.DANGER) }
    Button(onClick = {
      val value = amount.toLongOrNull()
      if (value == null || value <= 0) error = "Enter the amount." else onSave(Verdict(kind, direction, value, name.trim().ifEmpty { null }))
    }, colors = primaryButtonColors()) { Label("Save", weight = FontWeight.Medium, color = Ember.bg) }
  }
}

/** A transaction typed in by hand. Saved like any other, with source "Manual". */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NewTxSheet(onClose: () -> Unit) {
  val core = LocalContext.current.balance.core
  val view = LocalView.current
  val scope = rememberCoroutineScope()
  var direction by remember { mutableStateOf(Direction.OUT) }
  var amount by remember { mutableStateOf("") }
  var name by remember { mutableStateOf("") }
  var categoryId by remember { mutableStateOf<String?>(null) }
  var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
  var date by remember { mutableStateOf(LocalDate.now()) }
  var time by remember { mutableStateOf(LocalTime.now().withSecond(0).withNano(0)) }
  var picking by remember { mutableStateOf<String?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  var saving by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) { categories = runCatching { core.categories() }.getOrDefault(emptyList()) }

  Sheet(onClose) {
    Label("New transaction", size = 22.sp, weight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Chip("Spent", direction == Direction.OUT) { direction = Direction.OUT }
      Chip("Received", direction == Direction.IN) { direction = Direction.IN }
    }
    // The amount is the point of the sheet, so it's typed big, grouped as you go, in the color of its direction.
    Field(
      amount, { amount = amountInput(it); error = null }, "Amount (Rp)", Modifier.fillMaxWidth(), KeyboardType.Number, RupiahGrouping,
      textStyle = TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = if (direction == Direction.IN) Ember.income else Ember.text),
    )
    Field(name, { name = it }, "Name", Modifier.fillMaxWidth())
    AnimatedVisibility(direction == Direction.OUT && categories.isNotEmpty()) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("Category", size = 13.sp, weight = FontWeight.Medium, tone = Tone.MUTED)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          categories.forEach { c -> Chip(c.name, categoryId == c.id) { categoryId = if (categoryId == c.id) null else c.id } }
        }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
      PickerField("Date", Format.dayLabel(date), Modifier.weight(3f)) { picking = "date" }
      PickerField("Time", "%02d:%02d".format(time.hour, time.minute), Modifier.weight(2f)) { picking = "time" }
    }
    error?.let { Label(it, size = 14.sp, tone = Tone.DANGER) }
    Button(
      onClick = {
        val value = amount.toLongOrNull()
        if (value == null || value <= 0) { error = "Enter the amount."; return@Button }
        saving = true
        val at = minOf(LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), System.currentTimeMillis())
        scope.launch {
          runCatching { core.addTransaction(direction, value, name.trim().ifEmpty { null }, categoryId.takeIf { direction == Direction.OUT }, at) }
            .fold({ view.performHapticFeedback(HapticFeedbackConstants.CONFIRM); onClose() }, { error = "Couldn't save. Try again."; saving = false })
        }
      },
      enabled = !saving,
      colors = primaryButtonColors(),
    ) { Label("Add", weight = FontWeight.Medium, color = Ember.bg) }
  }

  when (picking) {
    "date" -> {
      val state = rememberDatePickerState(
        initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : androidx.compose.material3.SelectableDates {
          override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis()
        },
      )
      DatePickerDialog(
        onDismissRequest = { picking = null },
        confirmButton = {
          TextButton(onClick = {
            state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
            picking = null
          }) { Label("OK", weight = FontWeight.Bold) }
        },
      ) { DatePicker(state) }
    }
    "time" -> {
      val state = rememberTimePickerState(time.hour, time.minute, is24Hour = true)
      androidx.compose.ui.window.Dialog(onDismissRequest = { picking = null }) {
        Column(Modifier.clip(RoundedCornerShape(28.dp)).background(Ember.surface).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
          TimePicker(state)
          TextButton(onClick = { time = LocalTime.of(state.hour, state.minute); picking = null }, modifier = Modifier.align(Alignment.End)) {
            Label("OK", weight = FontWeight.Bold)
          }
        }
      }
    }
  }
}

@Composable
private fun PickerField(label: String, value: String, modifier: Modifier, onPress: () -> Unit) {
  Column(
    modifier.clip(RoundedCornerShape(14.dp)).background(Ember.bg).clickable(onClick = onPress).padding(horizontal = 16.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Label(label, size = 12.sp, tone = Tone.MUTED)
    Label(value, weight = FontWeight.Medium)
  }
}

/** Asks once, the first time there is something to check. A refusal is final until they turn Alerts on themselves. */
@Composable
fun rememberAlertsAsker(): () -> Unit {
  val context = LocalContext.current
  val core = context.balance.core
  val scope = rememberCoroutineScope()
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
  return remember {
    {
      scope.launch {
        if (core.setting("review_alerts_asked") == "1") return@launch
        core.setSetting("review_alerts_asked", "1")
        if (core.setting("review_alerts") == "0") return@launch
        if (Build.VERSION.SDK_INT >= 33 &&
          ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
  }
}
