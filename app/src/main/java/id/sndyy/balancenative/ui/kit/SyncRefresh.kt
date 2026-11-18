package id.sndyy.balancenative.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import id.sndyy.balancenative.balance
import id.sndyy.balancenative.core.AccountState
import id.sndyy.balancenative.ui.theme.Ember
import kotlinx.coroutines.launch

/**
 * Pull down to sync with the account: fetch what other devices logged and send this phone's changes.
 * Notifications are not read again, and screens reload on the ledger event. Local mode has no
 * account, so there is nothing to pull. Same look as the Expo app: orange spinner on a raised disc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncRefresh(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
  val account = LocalContext.current.balance.account
  val state by account.state.collectAsState()
  if (state !is AccountState.Signed) {
    Box(modifier.fillMaxSize(), content = content)
    return
  }
  val scope = rememberCoroutineScope()
  var running by remember { mutableStateOf(false) }
  val pull = rememberPullToRefreshState()
  PullToRefreshBox(
    isRefreshing = running,
    onRefresh = {
      if (running) return@PullToRefreshBox
      running = true
      scope.launch {
        account.refresh()
        running = false
      }
    },
    state = pull,
    modifier = modifier.fillMaxSize(),
    indicator = {
      PullToRefreshDefaults.Indicator(
        state = pull,
        isRefreshing = running,
        containerColor = Ember.raised,
        color = Ember.accent,
        modifier = Modifier.align(Alignment.TopCenter),
      )
    },
    content = content,
  )
}
