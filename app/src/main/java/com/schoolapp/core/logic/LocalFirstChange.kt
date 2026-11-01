package com.schoolapp.core.logic

import kotlinx.coroutines.CancellationException

/** The school server refused a change, so it was undone on this device as well. */
class ChangeRejectedException(cause: Throwable) : IllegalStateException(
    "The school server refused this change, so it was undone on this device" +
        (cause.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."),
    cause,
)

/**
 * A change that is made on this device first and then uploaded.
 *
 * Firestore keeps a write it cannot send yet (offline) queued and retries it itself, so an upload
 * that fails was refused by the server (permission denied, invalid data). Then the local change is
 * undone and [ChangeRejectedException] thrown, so the caller never reports a refused change as
 * done and the device does not keep data the server never accepted. A cancelled wait is not a
 * refusal: the write may still go through, so the local change stays.
 */
object LocalFirstChange {
    suspend fun run(
        apply: suspend () -> Unit,
        upload: suspend () -> Unit,
        undo: suspend () -> Unit,
    ) {
        apply()
        try {
            upload()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                undo()
            } catch (undoError: Exception) {
                e.addSuppressed(undoError)
            }
            throw ChangeRejectedException(e)
        }
    }
}
