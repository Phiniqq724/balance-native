package id.sndyy.balancenative.ui.main

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import id.sndyy.balancenative.Landing
import id.sndyy.balancenative.balance
import id.sndyy.balancenative.ui.activity.ActivityScreen
import id.sndyy.balancenative.ui.activity.ActivityViewModel
import id.sndyy.balancenative.ui.activity.NewTxSheet
import id.sndyy.balancenative.ui.kit.Label
import id.sndyy.balancenative.ui.kit.ledgerTicks
import id.sndyy.balancenative.ui.kit.CellButton
import id.sndyy.balancenative.ui.kit.SqueezeRow
import id.sndyy.balancenative.ui.kit.cellShape
import androidx.compose.foundation.layout.width
import id.sndyy.balancenative.ui.overview.OverviewScreen
import id.sndyy.balancenative.ui.profile.ProfileScreen
import id.sndyy.balancenative.ui.theme.Ember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
  Tab("Overview", Icons.Rounded.ShowChart),
  Tab("Activity", Icons.Rounded.ReceiptLong),
  Tab("Profile", Icons.Rounded.Person),
)

/** Swipeable pages with a floating pill of tabs; the add button rides beside it on Activity only. */
@Composable
fun MainScreen(landing: MutableStateFlow<Landing?>) {
  val pager = rememberPagerState { TABS.size }
  val scope = rememberCoroutineScope()
  val activity: ActivityViewModel = viewModel()
  val pendingLanding by landing.collectAsState()
  var adding by remember { mutableStateOf(false) }
  val core = LocalContext.current.balance.core
  // Same count as "To check" in Activity, live with every ledger write.
  val toCheck by remember { ledgerTicks().map { runCatching { core.unparsedCount() }.getOrDefault(0) } }.collectAsState(0)

  LaunchedEffect(pendingLanding) { if (pendingLanding != null) pager.animateScrollToPage(1) }

  Box(Modifier.fillMaxSize().background(Ember.bg)) {
    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { it }) { page ->
      when (page) {
        0 -> OverviewScreen(onCategory = { f ->
          activity.setFilter(f)
          scope.launch { pager.animateScrollToPage(1) }
        })
        1 -> ActivityScreen(
          landing = pendingLanding?.let { it.filter to it.fix },
          onLandingUsed = { landing.value = null },
          vm = activity,
        )
        else -> ProfileScreen()
      }
    }

    FloatingBar(
      selected = pager.targetPage,
      toCheck = toCheck,
      showAdd = pager.targetPage == 1,
      onSelect = { scope.launch { pager.animateScrollToPage(it, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) } },
      onAdd = { adding = true },
      modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp),
    )
  }

  if (adding) NewTxSheet(onClose = { adding = false })
}

/**
 * Compact floating nav, Tessera-style: three cell keys that share the bar by weight. The active one is
 * ink and wide enough for its label; a press swells the key and squeezes its neighbours. The add key
 * rides beside it on Activity only, neutral with an ink "+": orange stays for spending.
 */
@Composable
private fun FloatingBar(selected: Int, toCheck: Int, showAdd: Boolean, onSelect: (Int) -> Unit, onAdd: () -> Unit, modifier: Modifier) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Surface(color = Ember.raised, shape = cellShape(56.dp), shadowElevation = 8.dp) {
      SqueezeRow(
        count = TABS.size,
        modifier = Modifier.width(236.dp).padding(6.dp),
        spacing = 4.dp,
        chosen = selected,
        chosenExtra = 0.9f,
      ) { i, source, weight ->
        val tab = TABS[i]
        val on = i == selected
        CellButton(on = on, onClick = { if (!on) onSelect(i) }, modifier = weight, source = source, height = 44.dp, face = Ember.raised, description = tab.label) { fg ->
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (i == 1 && toCheck > 0) {
              BadgedBox(badge = {
                Badge(containerColor = if (on) Ember.bg else Ember.text, contentColor = if (on) Ember.text else Ember.bg) {
                  Label(if (toCheck > 99) "99+" else "$toCheck", size = 10.sp, weight = FontWeight.Bold, color = if (on) Ember.text else Ember.bg)
                }
              }) { Icon(tab.icon, tab.label, tint = fg, modifier = Modifier.size(22.dp)) }
            } else {
              Icon(tab.icon, tab.label, tint = fg, modifier = Modifier.size(22.dp))
            }
            AnimatedVisibility(on, enter = fadeIn() + expandHorizontally(spring(dampingRatio = Spring.DampingRatioLowBouncy)), exit = fadeOut() + shrinkHorizontally()) {
              Label(tab.label, size = 14.sp, weight = FontWeight.Bold, color = fg, maxLines = 1)
            }
          }
        }
      }
    }
    AnimatedVisibility(showAdd, enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(), exit = scaleOut() + fadeOut()) {
      CellButton(
        on = false,
        onClick = onAdd,
        modifier = Modifier.size(56.dp).shadow(8.dp, cellShape(56.dp)),
        height = 56.dp,
        face = Ember.raised,
        description = "New transaction",
      ) { Icon(Icons.Rounded.Add, "New transaction", tint = Ember.text, modifier = Modifier.size(26.dp)) }
    }
  }
}
