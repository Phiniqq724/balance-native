package id.sndyy.balancenative

import android.app.Application
import id.sndyy.balancenative.core.AccountManager
import id.sndyy.balancenative.core.BalanceCore

/** One ledger facade and one account owner for the whole process (UI, listener, workers). */
class BalanceApp : Application() {
  lateinit var core: BalanceCore
    private set
  lateinit var account: AccountManager
    private set

  override fun onCreate() {
    super.onCreate()
    core = BalanceCore(this)
    account = AccountManager(this, core)
  }
}

val android.content.Context.balance: BalanceApp get() = applicationContext as BalanceApp
