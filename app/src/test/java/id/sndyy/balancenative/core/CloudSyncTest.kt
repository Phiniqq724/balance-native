package id.sndyy.balancenative.core

import id.sndyy.balancenative.core.CloudSync.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncTest {
  private fun tx(id: String, amount: Long = 1000, at: Long = 1_000_000, category: String? = null) =
    CloudSync.Tx(id, "com.jago.digitalBanking", "out", amount, "Kopi", null, category, false, false, false, null, at)

  private fun state() = CloudSync.State(false, null, mutableMapOf(), mutableMapOf())

  @Test fun decideTable() {
    assertEquals(Verdict.FORGET, CloudSync.decide(null, null, "b"))
    assertEquals(Verdict.DROP, CloudSync.decide(null, "b", "b"))
    assertEquals(Verdict.KEEP, CloudSync.decide(null, "x", "b"))
    assertEquals(Verdict.AGREE, CloudSync.decide("a", "a", null))
    assertEquals(Verdict.TAKE, CloudSync.decide("a", null, null))
    assertEquals(Verdict.KEEP, CloudSync.decide("a", null, "b"))
    assertEquals(Verdict.TAKE, CloudSync.decide("a", "b", "b"))
    assertEquals(Verdict.TAKE, CloudSync.decide("a", "b", null))
    assertEquals(Verdict.KEEP, CloudSync.decide("a", "c", "b"))
  }

  @Test fun serverAndClientTimestampsAgree() {
    assertEquals(
      CloudSync.instant("2026-11-18T04:12:52.719+00:00"),
      CloudSync.instant("2026-11-18T04:12:52.719Z"),
    )
    assertEquals(1_793_000_000_123L, CloudSync.instant(java.time.Instant.ofEpochMilli(1_793_000_000_123L).toString()).toEpochMilli())
  }

  @Test fun cursorTracksNewestAsInstant() {
    val s = state()
    val r1 = CloudSync.Remote(tx("a"), "2026-11-18T04:00:00.5+00:00", false)
    val r2 = CloudSync.Remote(tx("b", at = 2), "2026-11-18T04:00:00.123456+00:00", false)
    CloudSync.planPull(s, emptyList(), listOf(r1, r2), emptyList(), emptyList())
    // String order would pick r2 ("…00.123456" > "…00.5"); time order picks r1.
    assertEquals("2026-11-18T04:00:00.5+00:00", s.cursor)
  }

  @Test fun newRemoteRowIsTakenAndRecorded() {
    val s = state()
    val pulled = CloudSync.planPull(s, emptyList(), listOf(CloudSync.Remote(tx("a"), "2026-11-18T04:00:00Z", false)), emptyList(), emptyList())
    assertEquals(1, pulled.changes.transactions.size)
    assertEquals(CloudSync.print(tx("a")), s.transactions["a"])
  }

  @Test fun twinWithLowerLocalIdMarksRemoteDuplicate() {
    val s = state()
    val pulled = CloudSync.planPull(s, emptyList(), listOf(CloudSync.Remote(tx("b"), "2026-11-18T04:00:00Z", false)), emptyList(), listOf(tx("a")))
    assertTrue("b" in pulled.duplicates)
    assertTrue(pulled.changes.transactions.isEmpty())
  }

  @Test fun twinWithLowerRemoteIdRekeysLocal() {
    val s = state()
    val pulled = CloudSync.planPull(s, emptyList(), listOf(CloudSync.Remote(tx("a"), "2026-11-18T04:00:00Z", false)), emptyList(), listOf(tx("b")))
    assertEquals(listOf(CloudRekey("b", "a")), pulled.changes.rekeys)
    assertTrue("b" in pulled.duplicates)
    assertEquals(CloudSync.print(tx("a")), s.transactions["a"])
  }

  @Test fun remoteDeleteOfUnchangedRowDrops() {
    val s = state().apply { transactions["a"] = CloudSync.print(tx("a")) }
    val pulled = CloudSync.planPull(s, emptyList(), listOf(CloudSync.Remote(tx("a"), "2026-11-18T04:00:00Z", true)), emptyList(), listOf(tx("a")))
    assertEquals(listOf("a"), pulled.changes.deletedTransactions)
    assertTrue("a" !in s.transactions)
  }

  @Test fun localEditSurvivesRemoteDelete() {
    val s = state().apply { transactions["a"] = CloudSync.print(tx("a")) }
    val pulled = CloudSync.planPull(s, emptyList(), listOf(CloudSync.Remote(tx("a"), "2026-11-18T04:00:00Z", true)), emptyList(), listOf(tx("a", category = "food")))
    assertTrue(pulled.changes.deletedTransactions.isEmpty())
  }
}
