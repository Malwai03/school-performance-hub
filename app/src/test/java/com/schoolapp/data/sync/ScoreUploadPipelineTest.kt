package com.schoolapp.data.sync

import com.google.common.truth.Truth.assertThat
import com.schoolapp.data.local.entity.ScoreEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScoreUploadPipelineTest {

    private fun score(id: String, marks: Double, synced: Boolean = false) =
        ScoreEntity(id, "assess_math", "student_$id", marks, "PRESENT", synced)

    /** Room stand-in with the same conditional acknowledgement as ScoreDao.markSyncedIfUnchanged. */
    private class FakeStore(vararg initial: ScoreEntity) : PendingScoreStore {
        val rows = linkedMapOf<String, ScoreEntity>().apply { initial.forEach { put(it.scoreId, it) } }

        /** When set, the next unsynced read waits here after taking its list (a paused worker). */
        var pauseAfterUnsyncedRead: CompletableDeferred<Unit>? = null

        /** A local edit: a new value, not uploaded yet. */
        fun edit(id: String, marks: Double) {
            rows[id] = rows.getValue(id).copy(obtainedMarks = marks, synced = false)
        }

        override suspend fun unsyncedIds(): List<String> {
            val ids = rows.values.filter { !it.synced }.map { it.scoreId }
            pauseAfterUnsyncedRead?.let { pauseAfterUnsyncedRead = null; it.await() }
            return ids
        }

        override suspend fun current(scoreId: String): ScoreEntity? = rows[scoreId]

        override suspend fun markSyncedIfUnchanged(uploaded: ScoreEntity): Boolean {
            val row = rows[uploaded.scoreId] ?: return false
            if (row.synced || row != uploaded.copy(synced = false)) return false
            rows[uploaded.scoreId] = row.copy(synced = true)
            return true
        }
    }

    /** Server stand-in that records every write in arrival order. */
    private class FakeRemote : ScoreRemote {
        val server = mutableMapOf<String, Double>()
        val writes = mutableListOf<Pair<String, Double>>()
        val refused = mutableSetOf<String>()

        /** An upload of this score waits here before the server takes it. */
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

        override suspend fun put(score: ScoreEntity) {
            gates.remove(score.scoreId)?.await()
            if (score.scoreId in refused) error("PERMISSION_DENIED")
            writes += score.scoreId to score.obtainedMarks
            server[score.scoreId] = score.obtainedMarks
        }
    }

    private fun TestScope.settle() = runCurrent()

    @Test
    fun `a worker paused after its unsynced read cannot overwrite a newer foreground save`() = runTest {
        val store = FakeStore(score("S", 60.0))
        val remote = FakeRemote()
        val pipeline = ScoreUploadPipeline()

        // The worker takes its list of unsynced scores (S at 60), then is held up.
        val resumeWorker = CompletableDeferred<Unit>()
        store.pauseAfterUnsyncedRead = resumeWorker
        val worker = async { pipeline.uploadAll(store, remote) { true } }
        settle()

        // The teacher changes S to 90 and the foreground save uploads it.
        store.edit("S", 90.0)
        assertThat(pipeline.uploadOne("S", store, remote)).isEqualTo(ScoreUploadOutcome.SYNCED)

        // The worker resumes with its old list: it must not send S again, least of all as 60.
        resumeWorker.complete(Unit)
        assertThat(worker.await()).isEqualTo(0)

        assertThat(remote.writes).containsExactly("S" to 90.0)
        assertThat(remote.server["S"]).isEqualTo(90.0)
        assertThat(store.rows.getValue("S")).isEqualTo(score("S", 90.0, synced = true))
    }

    @Test
    fun `uploads run one at a time and each sends the latest local version`() = runTest {
        val store = FakeStore(score("R", 40.0), score("S", 60.0))
        val remote = FakeRemote()
        val pipeline = ScoreUploadPipeline()

        // The worker is mid-upload of R when the teacher edits S and saves it.
        val releaseR = CompletableDeferred<Unit>()
        remote.gates["R"] = releaseR
        val worker = async { pipeline.uploadAll(store, remote) { true } }
        settle()
        store.edit("S", 90.0)
        val foreground = async { pipeline.uploadOne("S", store, remote) }
        settle()
        assertThat(remote.writes).isEmpty()

        releaseR.complete(Unit)
        assertThat(worker.await()).isEqualTo(0)
        foreground.await()

        // S went up exactly once, with the newer value; 60 was never sent.
        assertThat(remote.writes).containsExactly("R" to 40.0, "S" to 90.0).inOrder()
        assertThat(remote.server).containsExactly("R", 40.0, "S", 90.0)
        assertThat(store.rows.values.all { it.synced }).isTrue()
    }

    @Test
    fun `an acknowledgement that arrives after a newer edit leaves that edit pending`() = runTest {
        val store = FakeStore(score("S", 60.0))
        val remote = FakeRemote()
        val pipeline = ScoreUploadPipeline()

        val releaseS = CompletableDeferred<Unit>()
        remote.gates["S"] = releaseS
        val firstUpload = async { pipeline.uploadOne("S", store, remote) }
        settle()

        // Edited to 90 while 60 is still on its way to the server.
        store.edit("S", 90.0)
        releaseS.complete(Unit)

        assertThat(firstUpload.await()).isEqualTo(ScoreUploadOutcome.CHANGED_MEANWHILE)
        assertThat(store.rows.getValue("S")).isEqualTo(score("S", 90.0, synced = false))

        // Its own upload is what marks it synced, and the server ends on 90.
        assertThat(pipeline.uploadAll(store, remote) { true }).isEqualTo(0)
        assertThat(remote.server["S"]).isEqualTo(90.0)
        assertThat(store.rows.getValue("S")).isEqualTo(score("S", 90.0, synced = true))
    }

    @Test
    fun `a score the server refuses stays pending and is counted`() = runTest {
        val store = FakeStore(score("A", 10.0), score("B", 20.0))
        val remote = FakeRemote().apply { refused += "A" }

        assertThat(ScoreUploadPipeline().uploadAll(store, remote) { true }).isEqualTo(1)

        assertThat(store.rows.getValue("A").synced).isFalse()
        assertThat(store.rows.getValue("B").synced).isTrue()
        assertThat(remote.server).containsExactly("B", 20.0)
    }

    @Test
    fun `an account change stops the run before anything else is sent`() = runTest {
        val store = FakeStore(score("A", 10.0), score("B", 20.0), score("C", 30.0))
        val remote = FakeRemote()
        var sameAccount = true
        val switchAfterFirst = object : ScoreRemote {
            override suspend fun put(score: ScoreEntity) {
                remote.put(score)
                sameAccount = false
            }
        }

        assertThat(ScoreUploadPipeline().uploadAll(store, switchAfterFirst) { sameAccount }).isEqualTo(2)

        assertThat(remote.writes).containsExactly("A" to 10.0)
        assertThat(store.rows.values.count { !it.synced }).isEqualTo(2)
    }

    @Test
    fun `a foreground save waiting behind a sign-out is not sent under the next session`() = runTest {
        val store = FakeStore(score("R", 40.0), score("S", 60.0))
        val remote = FakeRemote()
        val pipeline = ScoreUploadPipeline()
        var sameAccount = true

        val releaseR = CompletableDeferred<Unit>()
        remote.gates["R"] = releaseR
        launch { pipeline.uploadOne("R", store, remote) { sameAccount } }
        settle()
        val waiting = async { pipeline.uploadOne("S", store, remote) { sameAccount } }
        settle()

        sameAccount = false
        releaseR.complete(Unit)

        assertThat(waiting.await()).isEqualTo(ScoreUploadOutcome.ACCOUNT_CHANGED)
        assertThat(remote.writes).containsExactly("R" to 40.0)
        assertThat(store.rows.getValue("S").synced).isFalse()
    }

    @Test
    fun `synced or deleted scores are not sent`() = runTest {
        val store = FakeStore(score("A", 10.0, synced = true))
        val remote = FakeRemote()
        val pipeline = ScoreUploadPipeline()

        assertThat(pipeline.uploadOne("A", store, remote)).isEqualTo(ScoreUploadOutcome.NOTHING_TO_DO)
        assertThat(pipeline.uploadOne("gone", store, remote)).isEqualTo(ScoreUploadOutcome.NOTHING_TO_DO)
        assertThat(remote.writes).isEmpty()
    }
}
