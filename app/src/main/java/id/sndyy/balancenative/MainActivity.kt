package id.sndyy.balancenative

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import id.sndyy.balancenative.core.AccountState
import id.sndyy.balancenative.ui.kit.UiEvents
import id.sndyy.balancenative.ui.main.MainScreen
import id.sndyy.balancenative.ui.signin.SignInScreen
import id.sndyy.balancenative.ui.theme.BalanceTheme
import id.sndyy.balancenative.ui.theme.Ember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Where a deep link asked to land: "review" opens Activity filtered to To check; [fix] opens that item's fix sheet. */
data class Landing(val filter: String?, val fix: Long?, val nonce: Long = System.nanoTime())

class MainActivity : ComponentActivity() {
  private val landing = MutableStateFlow<Landing?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    val splash = installSplashScreen()
    super.onCreate(savedInstanceState)
    val account = balance.account
    // Held until we know which ledger is open, so the sign-in screen never flashes for a signed-in user.
    splash.setKeepOnScreenCondition { account.state.value == AccountState.Loading }
    lifecycleScope.launch {
      account.load()
      if (account.state.value != AccountState.None) runCatching { balance.core.applyBundledRules() }
    }

    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )
    handle(intent)

    setContent {
      BalanceTheme {
        val state by account.state.collectAsState()
        Box(Modifier.fillMaxSize().background(Ember.bg)) {
          AnimatedContent(
            targetState = state is AccountState.None,
            transitionSpec = { (fadeIn(tween(320)) + scaleIn(tween(320), initialScale = 0.98f)) togetherWith fadeOut(tween(200)) },
            label = "root",
          ) { signedOut ->
            when {
              state == AccountState.Loading -> Unit
              signedOut -> SignInScreen()
              else -> MainScreen(landing)
            }
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handle(intent)
  }

  override fun onResume() {
    super.onResume()
    UiEvents.resumed()
    balance.core.ensureListenerBound()
    id.sndyy.balancenative.widget.Widgets.refresh(this)
    lifecycleScope.launch {
      // Re-open = re-read: rules may have changed with an app update, and the day may have rolled over.
      if (balance.account.state.value !is AccountState.None) runCatching { balance.core.applyBundledRules() }
    }
  }

  private fun handle(intent: Intent?) {
    val uri = intent?.data ?: return
    // Plain "balancenative://" (a widget tap) just opens the app where it was; only activity links navigate.
    if (uri.scheme != "balancenative" || uri.host != "activity") return
    // Any app can send this link, so the id is only a hint: Activity ignores one that isn't waiting in To check.
    landing.value = Landing(uri.getQueryParameter("f"), uri.getQueryParameter("fix")?.toLongOrNull()?.takeIf { it > 0 })
  }
}
