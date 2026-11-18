package id.sndyy.balancenative.data

enum class Direction(val value: String) {
  IN("in"), OUT("out");

  companion object {
    fun of(value: String?): Direction = if (value == "in") IN else OUT
  }
}

data class Transaction(
  val id: Long,
  val source: String,
  val direction: Direction,
  val amount: Long,
  val counterparty: String?,
  val bankCategory: String?,
  val categoryId: String?,
  val categoryName: String?,
  val isTransfer: Boolean,
  /** The other half of an own-account transfer, when both notifications arrived. */
  val pairId: Long?,
  val occurredAt: Long,
  val rawBody: String,
) {
  val isManual: Boolean get() = source == Apps.MANUAL
}

data class Category(val id: String, val name: String, val keywords: String)

enum class ReviewState { PENDING, SUGGESTED, FAILED }

/** What the AI reads a notification as. */
enum class AiKind(val value: String) {
  INCOME("income"), EXPENSE("expense"), TRANSFER("transfer"), AD_OR_OTHER("ad_or_other");

  companion object {
    fun of(value: String?): AiKind? = entries.firstOrNull { it.value == value }
  }
}

data class Review(
  val state: ReviewState,
  val kind: AiKind?,
  val direction: Direction?,
  val amount: Long?,
  val counterparty: String?,
  val confidence: Double?,
)

/** A money-looking notification no rule understood. Lives in "To check" until answered. */
data class Unparsed(
  val id: Long,
  val source: String,
  val title: String?,
  val body: String,
  val postedAt: Long,
  val review: Review?,
)

/** A spend or income event, without the notification text. Transfers are left out. */
data class Flow(
  val direction: Direction,
  val amount: Long,
  val categoryId: String?,
  val categoryName: String?,
  val title: String,
  val at: Long,
)

data class ActivityQuery(
  /** all, out, in, transfer, cat:<id>, cat:none. */
  val filter: String = "all",
  /** Lower-cased search text. */
  val q: String = "",
)

data class ActivityPage(
  val transactions: List<Transaction>,
  val total: Int,
  val spent: Long,
  val received: Long,
  val hasMore: Boolean,
)

data class InstalledApp(val packageName: String, val label: String, val icon: android.graphics.drawable.Drawable?)

object Apps {
  /** `source` of transactions typed in by hand. There is no app behind it. */
  const val MANUAL = "Manual"

  /** Watched out of the box: the banks the parsers were written against. */
  val DEFAULT_PACKAGES = listOf("com.jago.digitalBanking", "com.bca.mybca.omni.android", "com.bca")

  /** Indonesian banking and e-wallet apps, IDs checked against their Play Store listings. */
  val MONEY_APPS: Map<String, String> = linkedMapOf(
    "com.jago.digitalBanking" to "Jago",
    "com.bca.mybca.omni.android" to "myBCA",
    "com.bca" to "BCA mobile",
    "com.bcadigital.blu" to "blu",
    "id.co.bcasyariah.bsya" to "BSya",
    "id.bmri.livin" to "Livin",
    "id.co.bri.brimo" to "BRImo",
    "id.co.bankraya.apps" to "Raya",
    "id.bni.wondr" to "wondr",
    "src.com.bni" to "BNI Mobile",
    "co.id.bankbsi.superapp" to "BYOND",
    "com.bsm.activity2" to "BSI Mobile",
    "id.co.btn.mobilebanking.android" to "bale",
    "id.co.cimbniaga.mobile.android" to "OCTO",
    "com.dbank.mobile" to "D-Bank PRO",
    "net.myinfosys.PermataMobileX" to "Permata ME",
    "com.ocbcnisp.onemobileapp" to "OCBC",
    "id.com.uiux.mobile" to "Maybank2u",
    "com.panin.mobilepanin" to "MobilePanin",
    "com.msmile.bankmega" to "M-Smile",
    "com.dev.jakone.mbanking" to "JakOne",
    "com.bjbsmb" to "DIGI bjb",
    "com.mnc.mbanking" to "MotionBank",
    "com.btpn.dc" to "Jenius",
    "id.co.bankbkemobile.digitalbank" to "SeaBank",
    "com.alloapp.yump" to "Allo Bank",
    "com.bnc.finance" to "neobank",
    "com.krom.android" to "Krom",
    "com.hibank.mobile" to "hibank",
    "com.uob.id.digitalbank" to "TMRW",
    "com.dbs.id.pt.digitalbank" to "digibank",
    "com.gojek.gopay" to "GoPay",
    "com.gojek.app" to "Gojek",
    "ovo.id" to "OVO",
    "id.dana" to "DANA",
    "com.shopeepay.id" to "ShopeePay",
    "com.shopee.id" to "Shopee",
    "com.telkom.mwallet" to "LinkAja",
  )
}
