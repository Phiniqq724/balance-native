package id.sndyy.balancenative.ui.main

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
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
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
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
import id.sndyy.balancenative.ui.kit.rememberPressScale
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import id.sndyy.balancenative.ui.overview.OverviewScreen
import id.sndyy.balancenative.ui.profile.ProfileScreen
import id.sndyy.balancenative.ui.theme.Ember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Outlined at rest, filled when the tab is the one you're on. */
private data class Tab(val label: String, val icon: ImageVector, val active: ImageVector)

private val TABS = listOf(
  Tab("Overview", Icons.Outlined.GridView, Icons.Rounded.GridView),
  Tab("Activity", Icons.AutoMirrored.Outlined.ReceiptLong, Icons.AutoMirrored.Rounded.ReceiptLong),
  Tab("Profile", Icons.Outlined.Person, Icons.Rounded.Person),
)

/** The tab icon fills when it becomes active, with a small spring pop; leaving it just fades back to outline. */
@Composable
private fun TabIcon(tab: Tab, focused: Boolean, tint: androidx.compose.ui.graphics.Color) {
  AnimatedContent(
    targetState = focused,
    transitionSpec = {
      if (targetState) {
        (fadeIn(tween(120)) + scaleIn(spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), initialScale = 0.7f)) togetherWith fadeOut(tween(90))
      } else {
        fadeIn(tween(120)) togetherWith fadeOut(tween(90))
      }
    },
    label = "tabIcon",
  ) { on ->
    Icon(if (on) tab.active else tab.icon, tab.label, tint = tint, modifier = Modifier.size(24.dp))
  }
}

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

@Composable
private fun FloatingBar(selected: Int, toCheck: Int, showAdd: Boolean, onSelect: (Int) -> Unit, onAdd: () -> Unit, modifier: Modifier) {
  val view = LocalView.current
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Surface(color = Ember.raised, shape = RoundedCornerShape(28.dp), shadowElevation = 8.dp) {
      Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        // Counted-style squeeze on a bar that hugs its content: the pressed tab gains padding and its
        // neighbours give some up, all on one spring, so the pill breathes instead of scaling.
        val sources = remember { List(TABS.size) { MutableInteractionSource() } }
        val down = sources.map { it.collectIsPressedAsState().value }.indexOf(true)
        TABS.forEachIndexed { i, tab ->
          val focused = i == selected
          val pad by animateDpAsState(
            when { down < 0 -> 14.dp; down == i -> 22.dp; else -> 10.dp },
            spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
            label = "squeeze",
          )
          val bg by animateColorAsState(if (focused) Ember.selected else Ember.raised, label = "tabBg")
          val tint by animateColorAsState(if (focused) Ember.text else Ember.muted, label = "tabTint")
          val source = sources[i]
          Row(
            Modifier
              .clip(RoundedCornerShape(22.dp))
              .background(bg)
              .clickable(interactionSource = source, indication = null) {
                if (!focused) {
                  view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                  onSelect(i)
                }
              }
              .padding(horizontal = pad, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            if (i == 1 && toCheck > 0) {
              BadgedBox(badge = { Badge(containerColor = Ember.accent, contentColor = Ember.bg) { Label(if (toCheck > 99) "99+" else "$toCheck", size = 10.sp, weight = FontWeight.Bold, color = Ember.bg) } }) {
                TabIcon(tab, focused, tint)
              }
            } else {
              TabIcon(tab, focused, tint)
            }
            AnimatedVisibility(focused, enter = fadeIn() + expandHorizontally(spring(dampingRatio = Spring.DampingRatioLowBouncy)), exit = fadeOut() + shrinkHorizontally()) {
              Label(tab.label, size = 14.sp, weight = FontWeight.Bold, color = tint, maxLines = 1)
            }
          }
        }
      }
    }
    // The add button's slot (with its 8dp gap) grows and shrinks with it, so the centred pill glides
    // aside and back instead of jumping when the button finishes leaving.
    val slot = spring<androidx.compose.ui.unit.IntSize>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    AnimatedVisibility(
      showAdd,
      enter = expandHorizontally(slot, expandFrom = Alignment.Start) + scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.6f) + fadeIn(),
      exit = shrinkHorizontally(slot, shrinkTowards = Alignment.Start) + scaleOut(targetScale = 0.6f) + fadeOut(tween(120)),
    ) {
      val source = remember { MutableInteractionSource() }
      val scale = rememberPressScale(source, 0.9f)
      Box(
        Modifier
          .padding(start = 8.dp)
          .graphicsLayer { scaleX = scale; scaleY = scale }
          .shadow(8.dp, CircleShape)
          .size(56.dp)
          .clip(RoundedCornerShape(18.dp))
          .background(Ember.accent)
          .clickable(interactionSource = source, indication = null) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onAdd()
          },
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.Rounded.Add, "New transaction", tint = Ember.bg, modifier = Modifier.size(26.dp))
      }
    }
  }
}
