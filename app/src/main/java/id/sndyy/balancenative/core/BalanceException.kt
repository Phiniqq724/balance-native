package id.sndyy.balancenative.core

/** An error with a stable code the UI can map to copy. Replaces Expo's CodedException. */
open class BalanceException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)
