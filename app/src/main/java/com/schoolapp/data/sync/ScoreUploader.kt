package com.schoolapp.data.sync

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.schoolapp.data.local.SchoolDatabase
import com.schoolapp.data.local.entity.ScoreEntity
import kotlinx.coroutines.tasks.await

/**
 * Uploads local scores to schools/{schoolId}/scores. Used by [ScoreSyncWorker], foreground score
 * saves and the final upload at sign-out, all through one shared [ScoreUploadPipeline] so a
 * stale copy of a score can never overwrite a newer one on the server.
 */
internal object ScoreUploader {
    private val pipeline = ScoreUploadPipeline()

    /**
     * Uploads every unsynced score, one at a time, and returns how many are still unsynced.
     * Stops as soon as [stillSameAccount] turns false, so a sign-out or account switch mid-run
     * never sends this account's marks under another account.
     */
    suspend fun uploadUnsynced(
        db: SchoolDatabase,
        firestore: FirebaseFirestore,
        schoolId: String,
        stillSameAccount: () -> Boolean,
    ): Int = pipeline.uploadAll(RoomPendingScores(db), FirestoreScores(firestore, schoolId), stillSameAccount)

    /** Uploads the current local version of one score (a foreground save). */
    suspend fun uploadOne(
        db: SchoolDatabase,
        firestore: FirebaseFirestore,
        schoolId: String,
        scoreId: String,
        stillSameAccount: () -> Boolean,
    ): ScoreUploadOutcome =
        pipeline.uploadOne(scoreId, RoomPendingScores(db), FirestoreScores(firestore, schoolId), stillSameAccount)

    private class RoomPendingScores(private val db: SchoolDatabase) : PendingScoreStore {
        override suspend fun unsyncedIds(): List<String> = db.scoreDao().getUnsynced().map { it.scoreId }

        override suspend fun current(scoreId: String): ScoreEntity? = db.scoreDao().getById(scoreId)

        override suspend fun markSyncedIfUnchanged(uploaded: ScoreEntity): Boolean =
            db.scoreDao().markSyncedIfUnchanged(
                scoreId = uploaded.scoreId,
                assessmentId = uploaded.assessmentId,
                studentId = uploaded.studentId,
                obtainedMarks = uploaded.obtainedMarks,
                status = uploaded.status,
            ) > 0
    }

    private class FirestoreScores(
        private val firestore: FirebaseFirestore,
        private val schoolId: String,
    ) : ScoreRemote {
        override suspend fun put(score: ScoreEntity) {
            firestore.collection("schools")
                .document(schoolId)
                .collection("scores")
                .document(score.scoreId)
                .set(
                    mapOf(
                        "scoreId" to score.scoreId,
                        "assessmentId" to score.assessmentId,
                        "studentId" to score.studentId,
                        "obtainedMarks" to score.obtainedMarks,
                        "status" to score.status,
                    ),
                    SetOptions.merge(),
                )
                .await()
        }
    }
}
