package id.sndyy.balancenative.core

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BalanceDbTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Test fun aSwitchedLedgerFailsInsteadOfReopeningItsFile() {
    val stale = BalanceDb.get(context)
    stale.readableDatabase
    val file = context.getDatabasePath(stale.name)
    BalanceDb.closeCurrent()
    // As if a switch had moved the file away.
    context.deleteDatabase(stale.name)
    assertThrows(IllegalStateException::class.java) { stale.readableDatabase }
    assertThrows(IllegalStateException::class.java) { stale.writableDatabase }
    assertFalse("the old path stays empty", file.exists())
  }
}
