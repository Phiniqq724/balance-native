package id.sndyy.balancenative

import android.app.Application
import id.sndyy.balancenative.core.AccountManager
import id.sndyy.balancenative.core.BalanceCore
import id.sndyy.balancenative.core.ProfileStore

/** One ledger facade, one account owner and one profile for the whole process (UI, listener, workers). */
class BalanceApp : Application() {
  lateinit var core: BalanceCore
    private set
  lateinit var account: AccountManager
    private set
  lateinit var profiles: ProfileStore
    private set

  override fun onCreate() {
    super.onCreate()
    core = BalanceCore(this)
    account = AccountManager(this, core)
    profiles = ProfileStore(this, core)
  }
}

val android.content.Context.balance: BalanceApp get() = applicationContext as BalanceApp
