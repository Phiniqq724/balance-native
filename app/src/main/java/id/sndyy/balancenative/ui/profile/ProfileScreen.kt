package id.sndyy.balancenative.ui.profile

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import id.sndyy.balancenative.balance
import id.sndyy.balancenative.core.AccountState
import id.sndyy.balancenative.core.BalanceCore
import id.sndyy.balancenative.core.BalanceException
import id.sndyy.balancenative.core.SyncWorker
import id.sndyy.balancenative.data.Apps
import id.sndyy.balancenative.data.Category
import id.sndyy.balancenative.data.Format
import id.sndyy.balancenative.data.InstalledApp
import id.sndyy.balancenative.ui.activity.AppIcon
import id.sndyy.balancenative.ui.activity.Sheet
import id.sndyy.balancenative.ui.activity.primaryButtonColors
import id.sndyy.balancenative.ui.kit.Chip
import id.sndyy.balancenative.ui.kit.EmberSwitch
import id.sndyy.balancenative.ui.kit.Field
import id.sndyy.balancenative.ui.kit.Label
import id.sndyy.balancenative.ui.kit.Loaded
import id.sndyy.balancenative.ui.kit.Loading
import id.sndyy.balancenative.ui.kit.SectionHead
import id.sndyy.balancenative.ui.kit.Tone
import id.sndyy.balancenative.ui.kit.ledgerState
import id.sndyy.balancenative.ui.theme.Ember
import id.sndyy.balancenative.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class ProfileData(
  val categories: List<Category>,
  val watched: List<String>,
  val hasAccess: Boolean,
  val aiReview: Boolean,
  val alerts: Boolean,
  val aiKey: BalanceCore.AiKeyInfo?,
)

class ProfileViewModel(app: Application) : AndroidViewModel(app) {
  private val core = app.balance.core
  val apps = MutableStateFlow<List<InstalledApp>>(emptyList())

  val state = viewModelScope.ledgerState {
    ProfileData(
      categories = core.categories(),
      watched = core.watchedPackages(),
      hasAccess = core.hasNotificationAccess(),
      aiReview = core.aiReviewEnabled(),
      alerts = core.setting("review_alerts") != "0",
      aiKey = core.aiKeyInfo(),
    )
  }

  init {
    viewModelScope.launch { apps.value = core.installedApps() }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(vm: ProfileViewModel = viewModel()) {
  val context = LocalContext.current
  val core = context.balance.core
  val account = context.balance.account
  val accountState by account.state.collectAsState()
  val state by vm.state.collectAsState()
  val apps by vm.apps.collectAsState()
  val scope = rememberCoroutineScope()
  var editing by remember { mutableStateOf<Category?>(null) }
  var creating by remember { mutableStateOf(false) }
  var pickingApps by remember { mutableStateOf(false) }
  var keySheet by remember { mutableStateOf(false) }

  val data = (state as? Loaded.Ready)?.data ?: return Loading()
  val appByPackage = apps.associateBy { it.packageName }
  fun label(pkg: String) = appByPackage[pkg]?.label ?: core.sourceLabel(pkg)

  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()) {
    Header(accountState)

    SectionHead("Categories")
    FlowRow(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      data.categories.forEach { c -> Chip(c.name, false) { editing = c } }
      Chip("+ New", false) { creating = true }
    }

    SectionHead("Reading")
    AnimatedVisibility(!data.hasAccess) {
      SettingsRow("Notification access off", "Needed to log payments", tone = Tone.DANGER) { core.openNotificationAccessSettings() }
    }
    SettingsRow("Read from", data.watched.joinToString(", ") { label(it) }.ifEmpty { "Nothing yet" }, status = "${data.watched.size} on") { pickingApps = true }
    ToggleRow("AI review", "Reads what the rules can't", data.aiReview, enabled = accountState is AccountState.Signed || data.aiKey != null) { on ->
      scope.launch { core.setAiReview(on) }
    }
    ToggleRow("Alerts", "Ask me about unclear ones", data.alerts) { on -> scope.launch { core.setSetting("review_alerts", if (on) "1" else "0") } }
    SettingsRow("AI key", data.aiKey?.let { "${it.provider} ····${it.last4}" } ?: "Use your own Gemini, OpenAI or Claude key") { keySheet = true }

    SectionHead("Data")
    DataRows()

    SectionHead("Account")
    AccountRows(accountState)

    Spacer(Modifier.height(140.dp))
  }

  if (creating || editing != null) {
    CategorySheet(editing, onClose = { creating = false; editing = null })
  }
  if (pickingApps) AppPicker(apps, data.watched, onClose = { pickingApps = false }) { next -> scope.launch { core.setWatchedPackages(next) } }
  if (keySheet) AiKeySheet(data.aiKey) { keySheet = false }
}

@Composable
private fun Header(state: AccountState) {
  val (name, detail) = when (state) {
    is AccountState.Signed -> (state.user.name ?: state.user.email ?: "You") to (state.user.email ?: "Google")
    else -> "This phone" to "Nothing leaves it"
  }
  Row(Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(Ember.raised), contentAlignment = Alignment.Center) {
      Label(name.take(1).uppercase(), size = 22.sp, weight = FontWeight.Bold)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Label(name, size = 22.sp, weight = FontWeight.Bold, maxLines = 1)
      Label(detail, size = 13.sp, tone = Tone.MUTED, maxLines = 1)
    }
  }
}

@Composable
fun SettingsRow(title: String, subtitle: String? = null, status: String? = null, tone: Tone = Tone.TEXT, statusTone: Tone = Tone.MUTED, onPress: (() -> Unit)? = null) {
  Row(
    Modifier.fillMaxWidth().then(if (onPress != null) Modifier.clickable(onClick = onPress) else Modifier).padding(horizontal = Space.page, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Label(title, weight = FontWeight.Medium, tone = tone)
      if (subtitle != null) Label(subtitle, size = 13.sp, tone = Tone.MUTED, maxLines = 2)
    }
    if (status != null) Label(status, size = 13.sp, tone = statusTone)
  }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = Space.page, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Label(title, weight = FontWeight.Medium, tone = if (enabled) Tone.TEXT else Tone.MUTED)
      Label(subtitle, size = 13.sp, tone = Tone.MUTED)
    }
    EmberSwitch(checked, onChange, enabled = enabled)
  }
}

@Composable
private fun CategorySheet(category: Category?, onClose: () -> Unit) {
  val core = LocalContext.current.balance.core
  val scope = rememberCoroutineScope()
  var name by remember { mutableStateOf(category?.name.orEmpty()) }
  var keywords by remember { mutableStateOf(category?.keywords.orEmpty().replace(",", ", ")) }
  var error by remember { mutableStateOf<String?>(null) }
  Sheet(onClose) {
    Label(if (category == null) "New category" else "Edit category", size = 22.sp, weight = FontWeight.Bold)
    Field(name, { name = it }, "Name", Modifier.fillMaxWidth())
    Field(keywords, { keywords = it }, "Keywords, comma separated", Modifier.fillMaxWidth(), singleLine = false)
    error?.let { Label(it, size = 14.sp, tone = Tone.DANGER) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      Button(onClick = {
        if (name.isBlank()) { error = "Name it."; return@Button }
        val clean = keywords.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
        scope.launch { runCatching { core.saveCategory(category?.id, name, clean) }.fold({ onClose() }, { error = "Couldn't save." }) }
      }, colors = primaryButtonColors()) { Label("Save", weight = FontWeight.Medium, color = Ember.bg) }
      if (category != null) {
        TextButton(onClick = { scope.launch { runCatching { core.deleteCategory(category.id) }.fold({ onClose() }, { error = "Couldn't delete." }) } }) {
          Label("Delete", weight = FontWeight.Medium, tone = Tone.DANGER)
        }
      }
    }
  }
}

/** Known bank and e-wallet apps that are installed first, then everything else. */
@Composable
private fun AppPicker(apps: List<InstalledApp>, watched: List<String>, onClose: () -> Unit, onChange: (List<String>) -> Unit) {
  var selected by remember { mutableStateOf(watched.toSet()) }
  val sorted = remember(apps) { apps.sortedWith(compareBy({ it.packageName !in Apps.MONEY_APPS }, { it.label.lowercase() })) }
  Sheet({ onChange(sorted.map { it.packageName }.filter { it in selected } + selected.filter { p -> sorted.none { it.packageName == p } }); onClose() }) {
    Label("Read from", size = 22.sp, weight = FontWeight.Bold)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
      items(sorted, key = { it.packageName }) { app ->
        val on = app.packageName in selected
        Row(
          Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { selected = if (on) selected - app.packageName else selected + app.packageName }.padding(vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          AppIcon(app, app.label, size = 32)
          Label(app.label, Modifier.weight(1f), weight = FontWeight.Medium, maxLines = 1)
          EmberSwitch(on, { selected = if (it) selected + app.packageName else selected - app.packageName })
        }
      }
    }
  }
}

@Composable
private fun AiKeySheet(info: BalanceCore.AiKeyInfo?, onClose: () -> Unit) {
  val core = LocalContext.current.balance.core
  val scope = rememberCoroutineScope()
  var key by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  Sheet(onClose) {
    Label("AI key", size = 22.sp, weight = FontWeight.Bold)
    Label("Stays encrypted on this phone. The cheapest model is used.", size = 14.sp, tone = Tone.MUTED)
    if (info != null) Label("${info.provider} ····${info.last4}", weight = FontWeight.Medium)
    Field(key, { key = it.trim() }, "Paste a key", Modifier.fillMaxWidth())
    error?.let { Label(it, size = 14.sp, tone = Tone.DANGER) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(enabled = key.isNotEmpty() && !busy, onClick = {
        busy = true
        scope.launch {
          try {
            core.setAiKey(key); onClose()
          } catch (e: BalanceException) {
            error = e.message; busy = false
          } catch (e: Exception) {
            error = "Couldn't check the key."; busy = false
          }
        }
      }, colors = primaryButtonColors()) { Label(if (busy) "Checking…" else "Save", weight = FontWeight.Medium, color = Ember.bg) }
      if (info != null) TextButton(onClick = { scope.launch { core.clearAiKey(); onClose() } }) { Label("Remove", weight = FontWeight.Medium, tone = Tone.DANGER) }
    }
  }
}

@Composable
private fun DataRows() {
  val core = LocalContext.current.balance.core
  val scope = rememberCoroutineScope()
  var exportNote by remember { mutableStateOf<String?>(null) }
  var importNote by remember { mutableStateOf<String?>(null) }
  var preview by remember { mutableStateOf<Pair<Uri, Map<String, Any?>>?>(null) }

  val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
    uri ?: return@rememberLauncherForActivityResult
    exportNote = "Working…"
    scope.launch { exportNote = runCatching { core.exportLedger(uri) }.fold({ "${it.first} saved" }, { "Failed" }) }
  }
  val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri ?: return@rememberLauncherForActivityResult
    importNote = "Reading…"
    scope.launch {
      runCatching { core.previewImport(uri) }.fold(
        { preview = uri to it; importNote = null },
        { importNote = if ((it as? BalanceException)?.code == "ERR_NOT_LEDGER_FILE") "Not a Balance sheet" else "Failed" },
      )
    }
  }

  SettingsRow("Export", "Save as .xlsx", exportNote, statusTone = if (exportNote == "Failed") Tone.DANGER else Tone.MUTED) {
    export.launch("balance-${java.time.LocalDate.now()}.xlsx")
  }
  SettingsRow("Import", "Add from .xlsx", importNote, statusTone = if (importNote == "Failed" || importNote == "Not a Balance sheet") Tone.DANGER else Tone.MUTED) {
    import.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream"))
  }

  preview?.let { (uri, summary) ->
    AlertDialog(
      onDismissRequest = { preview = null },
      containerColor = Ember.surface,
      title = { Label("Import?", size = 20.sp, weight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          summary.forEach { (k, v) -> if (v != null && v != 0 && v != 0L) Label("$k: $v", tone = Tone.MUTED) }
        }
      },
      confirmButton = {
        TextButton(onClick = {
          preview = null
          importNote = "Working…"
          scope.launch { importNote = runCatching { core.importLedger(uri) }.fold({ "${it.added} added" }, { "Failed" }) }
        }) { Label("Import", weight = FontWeight.Bold) }
      },
      dismissButton = { TextButton(onClick = { preview = null }) { Label("Cancel", tone = Tone.MUTED) } },
    )
  }
}

@Composable
private fun AccountRows(state: AccountState) {
  val context = LocalContext.current
  val account = context.balance.account
  val scope = rememberCoroutineScope()
  var asking by remember { mutableStateOf<String?>(null) }
  var note by remember { mutableStateOf<String?>(null) }

  when (state) {
    is AccountState.Signed -> {
      val sync by remember { SyncWorker.status(context) }.collectAsState(SyncWorker.Status.IDLE)
      val last = SyncWorker.lastSynced(context)
      SettingsRow(
        "Sync",
        when (sync) {
          SyncWorker.Status.SYNCING -> "Syncing…"
          SyncWorker.Status.FAILED -> "Couldn't reach the cloud"
          else -> last?.let { "Synced ${Format.dayLabel(Format.date(it)).lowercase()} at ${Format.time(it)}" } ?: "Not yet"
        },
        tone = Tone.TEXT,
      ) { SyncWorker.request(context, 0) }
      SettingsRow("Use this phone only", status = note, statusTone = Tone.DANGER) { asking = "local" }
      SettingsRow("Sign out", tone = Tone.DANGER) { asking = "signOut" }
    }
    else -> {
      SettingsRow("Connect Google", "Back up and sync", status = note, statusTone = Tone.DANGER) {
        scope.launch { note = account.signInWithGoogle(context as Activity) }
      }
    }
  }

  asking?.let { what ->
    AlertDialog(
      onDismissRequest = { asking = null },
      containerColor = Ember.surface,
      title = { Label(if (what == "local") "Use this phone only?" else "Sign out?", size = 20.sp, weight = FontWeight.Bold) },
      text = { Label(if (what == "local") "Stops syncing. Nothing in the cloud is deleted." else "Your ledger stays on this phone for next time.", tone = Tone.MUTED) },
      confirmButton = {
        TextButton(onClick = {
          asking = null
          note = "Working…"
          scope.launch { note = if (what == "local") account.switchToLocal() else { account.signOut(); null } }
        }) { Label(if (what == "local") "Switch" else "Sign out", weight = FontWeight.Bold, tone = Tone.DANGER) }
      },
      dismissButton = { TextButton(onClick = { asking = null }) { Label("Cancel", tone = Tone.MUTED) } },
    )
  }
}
