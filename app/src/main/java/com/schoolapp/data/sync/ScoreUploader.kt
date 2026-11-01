package com.schoolapp.data.sync

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.schoolapp.data.local.SchoolDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Uploads unsynced local scores. Shared by [ScoreSyncWorker] and the final upload at sign-out. */
internal object ScoreUploader {
    /**
     * Uploads every unsynced score to schools/{schoolId}/scores, one at a time, and returns how
     * many are still unsynced. Stops as soon as [stillSameAccount] turns false, so a sign-out or
     * account switch mid-run never sends this account's marks under another account.
     */
    suspend fun uploadUnsynced(
        db: SchoolDatabase,
        firestore: FirebaseFirestore,
        schoolId: String,
        stillSameAccount: () -> Boolean,
    ): Int {
        var failed = 0
        val unsynced = db.scoreDao().getUnsynced()
        for ((index, score) in unsynced.withIndex()) {
            if (!stillSameAccount()) {
                failed += unsynced.size - index
                break
            }
            try {
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
                db.scoreDao().markSynced(score.scoreId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            }
        }
        return failed
    }
}
