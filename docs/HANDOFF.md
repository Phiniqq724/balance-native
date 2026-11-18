# Balance → Balance Native handoff

From the project-balance thread, 2026-11-18. The source app is at `/Users/sandy/Projects/Personal/project-balance` (below, paths are relative to it). `core/` means `modules/balance-core/android/src/main/java/expo/modules/balancecore/`.

Treat the code as the source of truth. If this brief and the code disagree, the code wins. Ask me.

---

## 0. Read this first: five rules for coexisting on one backend

1. **Use the exact same notification uid formula**: `UUID.nameUUIDFromBytes("${pkg}\n${sbn.postTime}\n${body}".toByteArray())`, where `body` = `EXTRA_BIG_TEXT ?: EXTRA_TEXT`, raw (not normalized). See `core/Ledger.kt:79` and `core/BalanceListenerService.kt`. Both apps on one phone then write the same cloud row instead of duplicates. That id becomes `transactions.id` in Supabase.
2. **Keep the seed category ids**: `food, transport, groceries, shopping, bills, subscriptions, fees` (`core/BalanceDb.kt` ~line 198). The cloud PK is `(user_id, id)`, so different ids create duplicate categories for the user.
3. **Supabase changes must be additive only.** Don't alter or drop existing columns, checks, triggers or policies. The Expo app is the user's daily driver.
4. **Deletes are soft** (`deleted_at`). Never hard-delete cloud rows. The other app learns about a deletion only through `deleted_at`.
5. **Don't write native APKs into `app_releases`.** The Expo app's in-app updater reads that table and would offer your APK. If you want an updater, use a separate table or an `app` column (additive).

Also needed for Google sign-in: add an **Android OAuth client for `id.sndyy.balancenative`** with your keystore's SHA-1 (debug and release) in the same Google Cloud project as the Web client. If it's missing or misspelled, you get the old "Choose an account" dialog and then nothing happens (it shows up as a cancel, `[16] Account reauth failed`). That cost us an evening.

---

## 1. Feature and screen map

Routes live in `src/app/`. The tab bar is a pill with a separate FAB beside it. The "+" (new transaction) appears only on Activity. Tabs swipe.

| Screen | File | What it does |
|---|---|---|
| Sign in | `src/app/sign-in.tsx` | Two choices: **Google** (Credential Manager bottom sheet) or **local mode** (no account, data stays on the phone). Signing in on a new phone restores from the cloud before the first push. |
| Overview | `src/app/(tabs)/index.tsx` | Period ticker (week / month / quarter / year, swipe to the previous period). Per-day spend line chart: current period solid, previous dashed, only today's or the scrubbed dot in accent (`core/FlowChartView.kt`, a Compose view). Pace vs the previous period. "Last 20 weeks" spending heat grid. "In and out" (In / Out / Net). "Where it went" (categories, tap → Activity filtered). "Where it came from". "Numbers" (Per day, Biggest, Payments, Most often). A banner when notification access is off. Math: `src/lib/analytics.ts`, `src/lib/heatmap.ts`. |
| Activity | `src/app/(tabs)/activity.tsx` | Day-grouped list with day headers that have a mini bar. Filter chips (All, categories, **To check**). Search (SQL-side, also matches the app name). Loads 25 at a time, always whole days. Tap a row → `tx-sheet.tsx` (category, transfer toggle, delete if manual). Items that need an answer (AI suggestions, unparsed money-looking notifications) sit here with Yes / Not money / Fix (`review.tsx`, `unparsed-card.tsx`). "+" → `new-tx-sheet.tsx` (direction, amount, who, category, a "When" date+time row). |
| Profile | `src/app/(tabs)/profile.tsx` | Avatar + username (edit sheet = one row of avatar and name; `profiles` table + private `avatars` bucket). **Categories** as chips plus "+ New" (`category-sheet.tsx`: name and keywords). **Reading**: notification access (row hidden once granted), **Read from** app grid plus a picker of installed apps, AI review switch, Alerts switch, AI key (bring your own Gemini / OpenAI / Claude key). **Data**: Export / Import `.xlsx`. **App**: in-app update. **Account**: sync status, cloud/local switch, sign out. |

Background (no UI): the notification listener logs transactions with the app closed. Widgets update with the app closed. A launcher badge plus a silent "count" notification on the `badge` channel show the to-check count. Review notifications have Yes / Not money / Fix actions (`core/ReviewNotifier.kt`).

Flows: sign in → grant notification access (banner, Profile) → bank notifications arrive → rows appear in Activity and Overview → unclear ones land in To check (badge, notification) → the user answers → each answer becomes a few-shot example for the AI.

---

## 2. `balance-core`: what lifts and what doesn't

There are 4,600 lines of Kotlin. Expo imports appear only in a few places (`grep "import expo" core/*.kt`).

**Lift almost as-is** (rename the package, swap `CodedException` for your own exception):
- `BalanceListenerService.kt`: listener, `requestRebind` on disconnect, midnight receiver, catch-up from `activeNotifications` on connect, group summaries skipped.
- `Parsers.kt`: rule engine, amount parsing, `normalize`, `Prefilter`.
- `Ledger.kt`: ingest → parse → insert, transfer pairing, categorize, manual rows, reprocess, AI review states. Replace `emitLedgerChanged()` (an Expo event) with a Flow.
- `BalanceDb.kt`: schema v5 and migrations. Since you start fresh you could collapse them, but keep the columns.
- `WidgetCharts.kt`: bitmap drawing for the DailySpend and DayByDay widgets.
- `Xlsx.kt` and `LedgerFile.kt`: a dependency-free xlsx reader and writer plus import planning. Strip the `CodedException` import.
- `Redact.kt`, `AiProviders.kt`, `AiReview.kt`, `AiKey.kt`: on-device AI calls with the user's own key, redaction before anything leaves the phone.
- `Account.kt`: one ledger file per user (`balance-<uid>.db`, `balance-signed-out.db`), active user in SharedPreferences `balance.account`.
- `CloudAuth.kt`: minimal Supabase token refresh over HTTP so the background code can reach the cloud.
- `GoogleSignIn.kt`: Credential Manager with nonce. The raw nonce goes to Supabase `signInWithIdToken`.
- `ReviewNotifier.kt`.

**Coupled to Expo, rewrite:**
- `BalanceCoreModule.kt`: the JS bridge. It's your list of the operations the UI needs (each `AsyncFunction` = one ViewModel or repository method). Delete the rest.
- `WidgetBridge.kt`: computes widget props, then writes them into **expo-widgets' internal SharedPreferences** (`expo.modules.widgets`, key `__expo_widgets_<Name>_props`) and broadcasts an update. Keep the prop computation and swap the push for Glance `updateAll` / state.
- `WidgetResizedReceiver.kt`: listens for an action that only exists because of our expo-widgets patch. With Glance, use `onAppWidgetOptionsChanged` directly.
- `FlowChartView.kt`: the chart is Compose already. Strip the `ExpoUIView` / `ComposeProps` wrapper and keep the drawing.
- `LedgerMerge.kt`: uses Expo `Record` / `@Field` for JS → native sync payloads. Turn these into plain data classes.

**Single SQLite owner.** The listener service and the UI run in one process and must share one open `BalanceDb` (`BalanceDb.get()` is a synchronized singleton, writes go through `Ledger.write`). The Expo app removed expo-sqlite for exactly this reason, since two connections from two libraries risked corruption. Room is fine if it's the only owner.

---

## 3. Parsers

- **Rules:** `src/lib/parse-rules.ts` (all patterns and the reasoning behind them). The engine is `core/Parsers.kt`. In the Expo app the rules are JSON pushed into prefs `balance.parser` so they ship by OTA. You have no OTA, so see section 7.
- `{amount}` expands to `(?:Rp|IDR)\.?\s?([\d.,]+)` and is capture group 1. `who` and `category` are group numbers. Matching is case-insensitive against the whole normalized text.
- **Normalize:** strip zero-width chars `​-‍⁠﻿` (Jago puts them inside names: `FAHRELL S​. Z​. W​.`), strip footers (`Need help?…`, `Butuh bantuan?…`), collapse whitespace, trim trailing dots.
- **Amount:** the last `.` or `,` counts as the decimal point only if 1–2 digits follow it. So `Rp 1.000` → 1000, `IDR 1,000.00` → 1000, `Rp10.000` → 10000. Stored as whole rupiah (Long).
- **Names:** Jago wraps some merchants in dots (`ke .DUNIA ELEKTRIK..`). `cleanName` trims them.
- **Jago "paid":** `to NAME, Perleng`. After the comma is Jago's merchant type, which goes into `bank_category`.
- **Ignore, not money:** Jago Pocket moves (`has been moved from your … to your …`, `You've moved … out of your …`).
- **BCA mobile (`com.bca`)** puts the title inside the text (`Financial Diary: Pengeluaran sebesar …`). myBCA (`com.bca.mybca.omni.android`) keeps it as a separate title. One optional prefix handles both.
- **Generic fallback** for apps the user picked that have no rules: an amount plus exactly one direction word (out or in lists), promo words excluded. When AI is on, picked apps skip this and go to AI review instead.
- **Statuses** on `raw_notifications`: `pending → parsed | unparsed | ignored`.
- **Samples:** `notif-samples.txt` is a small adb dump (62 lines, English only) from `scripts/dump-notifications.sh`. More real samples, including Indonesian ones, are in Supabase `ai_examples` (already redacted). **The Jago Indonesian rules marked "No sample yet" in `parse-rules.ts` have not been verified.**
- Default watched packages: `com.jago.digitalBanking`, `com.bca.mybca.omni.android`, `com.bca`.

Turn this corpus into JVM unit tests on day one. It's the cheapest safety net you'll get.

---

## 4. Sync and auth

Code: `src/lib/sync.ts` (JS) plus `core/LedgerMerge.kt` (applies the changes natively).

- **Model:** the phone ledger is the source of truth and the cloud is the shared copy. Raw notification text never leaves the phone.
- **`sync_base(kind, id, print)`:** for each row, the fingerprint both sides last agreed on. `print` = JSON array of the synced fields (`categoryPrint` / `transactionPrint` in sync.ts). Timestamps are compared as epoch ms because the server echoes `+00:00` and the phone sends `Z`.
- **Settings:** `sync_user` (whose base this is; a different user → reset) and `sync_cursor` (newest `updated_at` seen).
- **Order:** **pull, then push.** Categories go before transactions.
- **Pull:** fetch rows with `updated_at > cursor − 2 min` (overlap for out-of-order commits; re-reading is harmless), pages of 1000, deleted rows included. For each row, `decide(remote, local, base)` returns:
  - `agree`: same on both sides.
  - `take`: changed only remotely, or never synced from here. The cloud wins.
  - `drop`: deleted remotely and unchanged locally. Delete here.
  - `forget`: gone on both sides.
  - `keep`: local changes win, and the push sends them.
- **Duplicate merging:** if a remote row is new here but matches a local row on `source|occurred_at|direction|amount` (built from legacy random uids), the **lower id wins**. Either the remote one is marked a duplicate (soft-deleted on push), or the local row is re-keyed to the remote id.
- **Push:** upsert rows whose print differs from the base (chunks of 500, `deleted_at: null`), then soft-delete ids that are in the base but gone locally. Record the base after each chunk.
- **Cloud transaction `pair_id`** = the partner's uid, not its local integer id.
- **When it runs:** only while the app is open. On open, 3 s after any ledger change (debounced), and on resume. Runs are serialized (one running, at most one queued). Pull-to-refresh = sync only (no re-read).
- **Auth:** Google only, via Credential Manager → ID token with a SHA-256-hashed nonce → Supabase `signInWithIdToken` with the raw nonce. The Web client ID is in the Expo app's `.env` (`EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID`). Supabase project ref `uzhgghcnpueakvyaypuz`. The session is stored in SharedPreferences `balance.account` under an auth-item prefix (`core/Account.kt`). In native, `supabase-kt` with a custom `SessionManager` works, or keep `CloudAuth.kt`.
- **Local mode:** no account and no sync. Switching from cloud to local just stops syncing and deletes nothing in the cloud. Data moves between ledger files with `Account.move`.
- **Account deletion:** the `delete_account()` SQL function exists. The user asked to remove the button.

---

## 5. Supabase schema (`supabase/migrations/`)

All tables have RLS: `auth.uid() = user_id` (or `id` for `profiles`), `anon` revoked. A `touch_updated_at` trigger means **the server owns `updated_at`**. Never send it.

- `categories(user_id, id, name, keywords, position, updated_at, deleted_at)`, PK `(user_id, id)`.
- `transactions(id uuid PK, user_id, source, direction in/out, amount bigint, counterparty, bank_category, category_id, category_locked, is_transfer, transfer_locked, pair_id, occurred_at, updated_at, deleted_at)`. FK `(user_id, category_id)` → categories, `on delete set null`.
- `user_settings(user_id, key, value)`: `key` is CHECK-limited to `'self_names'` (unused now).
- `profiles(id, username ^[a-z0-9_.]{3,20}$ unique, avatar_path, updated_at)` and the private `avatars` bucket, path `<uid>/<ms>.jpg`, JPEG only, ≤ 1 MB.
- `ai_examples` (redacted text plus the user's answer, read as few-shot examples) and `ai_requests` (per-user rate limit, insert-only for users). Both apps share the same quota.
- `app_releases`: see rule 5 above.
- Edge functions: `classify-notification` (Gemini via the `GEMINI_API_KEY` secret, `verify_jwt` on, redaction in `_shared/redact.ts`) and `publish-release` (called by the release workflow, shared secret).

Things you **can** do safely: new tables, new nullable columns, new functions. Things you **can't**: change a CHECK, rename columns, reuse a field to mean something different.

---

## 6. Widgets

All data is computed in `core/WidgetBridge.kt` + `core/WidgetCharts.kt`. The layouts are in `src/widgets/*.tsx`.

| Widget | Shows |
|---|---|
| SpendGrid (resizable) | GitHub-style heat grid, ember shades `#2B2420 → #FF7444`. Below 250dp wide it switches to a short "today" header. Drawn as a bitmap for the exact size. |
| MonthPulse | Month name, spent, received, today's spend (compact numbers). |
| DailySpend 4×2 | Rolling 30-day daily spend chart (PNG). |
| DayByDay 2×2 | Day-by-day bars (PNG). |

**Refresh triggers:** every ledger write, midnight (`ACTION_DATE_CHANGED` / time-change receiver in the listener service), widget resize, app open.

**Why expo-widgets was patched** (`patches/expo-widgets@58.0.23.patch`): (1) to broadcast resize events, because the grid bitmap has to be redrawn for the new size, and (2) an `open:` interaction target so a tap opens the app (`actionStartActivity`). The picker previews are PNGs from `scripts/make-widget-previews.py` injected by a config plugin. In plain Glance you get all three natively (`onAppWidgetOptionsChanged`, `actionStartActivity`, `previewImage` / `previewLayout`).

---

## 7. Bugs already fixed, and what I'd fix if starting fresh

**Fixed. Don't bring these back:**
- The listener silently stopped after the OS killed it → `requestRebind` in `onListenerDisconnected` plus catch-up from `activeNotifications` on connect (v1.2.1).
- Group summary notifications were logged twice → skip `FLAG_GROUP_SUMMARY`.
- Zero-width characters in Jago names broke matching and display.
- Jago Pocket moves were counted as spending.
- Old push-only sync soft-deleted the other build's rows, and random uids created duplicates (14 pairs found) → two-way sync plus deterministic uids plus the lower-id merge.
- Recycled list rows showed one card's answer on another (inbox) → stable keys, no recycling for stateful cards.
- Opening a sheet rebuilt the rows after it, which blinked → sheets live in a fixed slot.
- Server `+00:00` vs client `Z` made every row look changed → compare epoch ms.
- Widgets showed yesterday after midnight with no new notification → date-change receiver.
- Gemini's newer `AQ.` key prefix was rejected by validation.
- Two SQLite owners in one process → single owner.

**Flaws I'd fix fresh. Flag each one to the user as a change:**
1. **Sync runs only while the app is open.** Notifications that arrive with the app closed reach the cloud only on the next open. Use WorkManager (expedited on ledger change, periodic as a fallback, network constraint).
2. **Sync rescans the whole ledger** every run (`localTransactions()` loads every row). Fine at hundreds of rows, slow at tens of thousands. Consider a dirty flag or `local_updated_at`.
3. **Cursor is string-compared** plus a 2-minute overlap hack. Parse it into an `Instant`.
4. **Parse rules need a home.** Without OTA, either bundle them and fetch overrides from a new `parse_rules` table, or ship them in the APK only. Ask the user.
5. **Generic keyword guess** with AI off still mislabels some promos. An open question from October: should picked apps go straight to To check instead? Ask.
6. **Unverified Jago Indonesian rules** (marked in `parse-rules.ts`).
7. **Transfer pairing** is any opposite-direction, same-amount row from a different source within 15 minutes (`TRANSFER_PAIR_WINDOW_MS`). Two coincidental same-amount payments get paired. Consider a confirm step or a counterparty match.
8. **Widgets depend on library internals.** Glance with your own state removes the whole category of problem.
9. **Category keywords** use a first-match-wins order (`position`). Users can't see why something was categorized. A "matched: kopi" hint would help.
10. **No tests.** The parsers and `decide()` are pure functions, so they're easy to unit-test.

---

## 8. What the user likes

- **Copy:** English, short, one line. They will never accept long descriptions or helper paragraphs. Labels like "Read from", "Where it went", "To check".
- **Design:** "Ember" (`DESIGN.md`): dark only, warm charcoal, Satoshi (Fontshare) Regular/Medium/Bold. Numbers are the focal point (big bold digits, a small muted "Rp"). Hairline rules instead of stacked cards. **Each color has one meaning:** orange `#FF7444` = spending heat, sage `#9BCB9F` = money in, pink `#F07AAE` = errors. Money out gets no color, just a minus sign ("spending isn't an error"). Tokens are in `src/theme.ts`, including the Material color overrides that stop dynamic color.
- **They hate generic or "AI slop" UI.** Show palette or design options before applying them. Ask one decisive question rather than guess. Research rather than invent.
- They found the current app "kind of boring". Motion and feel are open to experiment. The Expo app has motion dial 1, and they've invited more here.
- **Don't test on their phone.** No adb taps, screen recording or UI driving. You build, they test. Installing a debug build when asked is fine.
- They pick version names themselves. Don't assume semver bumps.
- **One task at a time.** They watch token usage, so use smaller models for sub-agents.
- "Push it" in the Expo app means push a `vX.Y.Z` tag and let the workflow build. You'll need your own release process. Ask.
- Don't touch settings on their daily app (`com.projectbalance.app`). A test tap once switched off a watched bank.

Ask me anything about how the Expo app behaves today. I'll answer with file paths. Good luck, have fun with it 🔥
