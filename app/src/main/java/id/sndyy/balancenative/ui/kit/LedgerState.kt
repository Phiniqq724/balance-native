package id.sndyy.balancenative.ui.kit

import id.sndyy.balancenative.core.BalanceCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

sealed interface Loaded<out T> {
  data object Loading : Loaded<Nothing>
  data class Error(val error: Throwable) : Loaded<Nothing>
  data class Ready<T>(val data: T) : Loaded<T>
}

/** App-wide signals the screens re-read on: the app coming back to the front (the day may have changed). */
object UiEvents {
  private val _resumed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val resumed: Flow<Unit> = _resumed
  fun resumed() { _resumed.tryEmit(Unit) }

  private val _retry = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val retry: Flow<Unit> = _retry
  fun retry() { _retry.tryEmit(Unit) }
}

/**
 * Fires once now, then on every ledger write, resume or retry. Bursts (a sync applying rows in chunks)
 * settle for 150 ms first, so they cost one re-read, not one per write. The first read never waits.
 */
@OptIn(FlowPreview::class)
fun ledgerTicks(): Flow<Unit> = merge(BalanceCore.changes, UiEvents.resumed, UiEvents.retry).debounce(150).onStart { emit(Unit) }.conflate()

/**
 * Runs a ledger read and re-runs it whenever the listener writes something new or the app comes
 * back to the front. Keeps the previous data on screen until the new read lands. Port of use-ledger.ts.
 */
fun <T> CoroutineScope.ledgerState(load: suspend () -> T): StateFlow<Loaded<T>> =
  ledgerTicks()
    .transformLatest {
      emit(runCatching { load() }.fold<Loaded<T>, T>({ Loaded.Ready(it) }, { Loaded.Error(it) }))
    }
    .stateIn(this, SharingStarted.WhileSubscribed(5_000), Loaded.Loading)

/** Same, but also re-runs when [input] changes. */
fun <I, T> CoroutineScope.ledgerState(input: Flow<I>, load: suspend (I) -> T): StateFlow<Loaded<T>> =
  combine(ledgerTicks(), input) { _, i -> i }
    .mapLatest { i -> runCatching { load(i) }.fold<Loaded<T>, T>({ Loaded.Ready(it) }, { Loaded.Error(it) }) }
    .stateIn(this, SharingStarted.WhileSubscribed(5_000), Loaded.Loading)
