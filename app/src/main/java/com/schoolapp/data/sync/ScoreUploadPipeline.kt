package com.schoolapp.data.sync

import com.schoolapp.data.local.entity.ScoreEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Local side of the score upload queue (Room in the app, a fake in tests). */
internal interface PendingScoreStore {
    /** Ids of the scores that still have to be uploaded. */
    suspend fun unsyncedIds(): List<String>

    /** The score as it is in the local database right now, or null if it was deleted. */
    suspend fun current(scoreId: String): ScoreEntity?

    /**
     * Marks the score uploaded only if the local row still holds exactly what was uploaded.
     * Returns false when it was edited (or deleted) while the upload was in flight.
     */
    suspend fun markSyncedIfUnchanged(uploaded: ScoreEntity): Boolean
}

/** Remote side: writes one score to the server and returns once the server has accepted it. */
internal fun interface ScoreRemote {
    suspend fun put(score: ScoreEntity)
}

internal enum class ScoreUploadOutcome {
    /** The uploaded version is the local one: marked synced. */
    SYNCED,

    /** Already synced, or deleted locally: nothing was sent. */
    NOTHING_TO_DO,

    /** Edited while it was being uploaded: still pending, so the newer version goes up next. */
    CHANGED_MEANWHILE,

    /** The server did not accept it: still pending. */
    FAILED,

    /** The signed-in account changed before its turn came: not sent, still pending. */
    ACCOUNT_CHANGED,
}

/**
 * The one ordered upload pipeline for scores, shared by the sync worker, foreground saves and
 * the last upload at sign-out.
 *
 * - Score uploads run one at a time, so they reach the server in the order they were sent.
 * - Each upload reads the score from the local database when its turn comes, never from an
 *   earlier snapshot, so an older version can never be sent after a newer one.
 * - An upload is acknowledged only if the local row still holds the uploaded version; a score
 *   edited mid-upload stays pending until its own version has been accepted.
 */
internal class ScoreUploadPipeline(
    private val mutex: Mutex = Mutex(),
) {
    suspend fun uploadOne(
        scoreId: String,
        store: PendingScoreStore,
        remote: ScoreRemote,
        stillSameAccount: () -> Boolean = { true },
    ): ScoreUploadOutcome =
        mutex.withLock {
            // Checked once it is this upload's turn: waiting for the lock can outlast a sign-out.
            if (!stillSameAccount()) return@withLock ScoreUploadOutcome.ACCOUNT_CHANGED
            val score = store.current(scoreId)
            if (score == null || score.synced) return@withLock ScoreUploadOutcome.NOTHING_TO_DO
            try {
                remote.put(score)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock ScoreUploadOutcome.FAILED
            }
            if (store.markSyncedIfUnchanged(score)) {
                ScoreUploadOutcome.SYNCED
            } else {
                ScoreUploadOutcome.CHANGED_MEANWHILE
            }
        }

    /**
     * Uploads every pending score and returns how many of them are still pending. Stops as soon
     * as [stillSameAccount] turns false, so an account switch mid-run never sends this
     * account's marks under another account.
     */
    suspend fun uploadAll(
        store: PendingScoreStore,
        remote: ScoreRemote,
        stillSameAccount: () -> Boolean,
    ): Int {
        val ids = store.unsyncedIds()
        var stillPending = 0
        for ((index, scoreId) in ids.withIndex()) {
            when (uploadOne(scoreId, store, remote, stillSameAccount)) {
                ScoreUploadOutcome.SYNCED, ScoreUploadOutcome.NOTHING_TO_DO -> Unit
                ScoreUploadOutcome.CHANGED_MEANWHILE, ScoreUploadOutcome.FAILED -> stillPending++
                ScoreUploadOutcome.ACCOUNT_CHANGED -> {
                    stillPending += ids.size - index
                    break
                }
            }
        }
        return stillPending
    }
}
