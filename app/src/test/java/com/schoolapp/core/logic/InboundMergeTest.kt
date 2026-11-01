package com.schoolapp.core.logic

import com.google.common.truth.Truth.assertThat
import com.schoolapp.core.logic.InboundMerge.Coverage
import com.schoolapp.core.logic.InboundMerge.ServerDoc
import org.junit.Test

class InboundMergeTest {

    /** A stand-in row: a mark with its assessment + student. */
    private data class Mark(val id: String, val assessmentId: String, val studentId: String, val marks: Double)

    private fun mark(id: String, marks: Double, assessmentId: String = "a1", studentId: String = id) =
        Mark(id, assessmentId, studentId, marks)

    private fun doc(row: Mark, pending: Boolean = false) = ServerDoc(row.id, row, pending)

    private fun local(id: String, dirty: Boolean = false, assessmentId: String = "a1", studentId: String = id) =
        LocalRow(id, mapOf("assessmentId" to assessmentId), dirty, conflictKey = "$assessmentId|$studentId")

    private fun plan(
        server: List<ServerDoc<Mark>>,
        localRows: List<LocalRow>,
        coverage: Coverage = Coverage.IdRange(null, null),
        inScope: (LocalRow) -> Boolean = { true },
        extraDirtyIds: Set<String> = emptySet(),
    ) = InboundMerge.plan(
        server = server,
        candidates = localRows,
        dirtyIds = localRows.filter { it.dirty }.mapTo(HashSet()) { it.id } + extraDirtyIds,
        dirtyConflictKeys = localRows.filter { it.dirty }.mapNotNullTo(HashSet()) { it.conflictKey },
        inScope = inScope,
        coverage = coverage,
        conflictKeyOf = { "${it.assessmentId}|${it.studentId}" },
    )

    @Test
    fun `the server's version replaces a synced local row`() {
        val result = plan(listOf(doc(mark("s1", 75.0))), listOf(local("s1")))
        assertThat(result.upserts).containsExactly(mark("s1", 75.0))
        assertThat(result.deleteIds).isEmpty()
        assertThat(result.keptLocalIds).isEmpty()
    }

    @Test
    fun `a mark not uploaded yet is never overwritten by the download`() {
        val result = plan(listOf(doc(mark("s1", 60.0))), listOf(local("s1", dirty = true)))
        assertThat(result.upserts).isEmpty()
        assertThat(result.keptLocalIds).containsExactly("s1")
    }

    @Test
    fun `a change made on this device after the page was fetched is not overwritten`() {
        val result = plan(listOf(doc(mark("s1", 60.0))), listOf(local("s1")), extraDirtyIds = setOf("s1"))
        assertThat(result.upserts).isEmpty()
        assertThat(result.keptLocalIds).containsExactly("s1")
    }

    @Test
    fun `a document with this device's own queued write is skipped`() {
        val result = plan(listOf(doc(mark("s1", 90.0), pending = true)), listOf(local("s1")))
        assertThat(result.upserts).isEmpty()
        assertThat(result.keptLocalIds).containsExactly("s1")
    }

    @Test
    fun `a server mark for the same student and assessment as a pending local mark does not land beside it`() {
        // The local mark has another id (made on this device); the server has its own copy.
        val pending = local("local-id", dirty = true, studentId = "stu-1")
        val result = plan(listOf(doc(Mark("server-id", "a1", "stu-1", 40.0))), listOf(pending))
        assertThat(result.upserts).isEmpty()
        assertThat(result.keptLocalIds).contains("server-id")
    }

    @Test
    fun `a synced row the server no longer has is removed, an unsynced one is kept`() {
        val result = plan(emptyList(), listOf(local("gone"), local("pending", dirty = true)))
        assertThat(result.deleteIds).containsExactly("gone")
        assertThat(result.keptLocalIds).containsExactly("pending")
    }

    @Test
    fun `deletions are only concluded inside the page's id range`() {
        val rows = listOf(local("a"), local("c"), local("e"), local("g"))
        // Page covered ids after "b" through "e" and returned only "c".
        val result = plan(listOf(doc(mark("c", 50.0))), rows, Coverage.IdRange("b", "e"))
        assertThat(result.deleteIds).containsExactly("e")
    }

    @Test
    fun `the empty last page covers everything after the cursor`() {
        val rows = listOf(local("a"), local("m"), local("z"))
        assertThat(plan(emptyList(), rows, Coverage.IdRange("b", null)).deleteIds).containsExactly("m", "z")
    }

    @Test
    fun `a changes-only download never deletes`() {
        assertThat(plan(emptyList(), listOf(local("a")), Coverage.ChangesOnly).deleteIds).isEmpty()
    }

    @Test
    fun `an exact-id fetch covers exactly its rows`() {
        val result = plan(listOf(doc(mark("a", 1.0))), listOf(local("a"), local("b")), Coverage.Everything)
        assertThat(result.deleteIds).containsExactly("b")
    }

    @Test
    fun `rows outside the query's scope are left to their own query`() {
        val rows = listOf(local("x", assessmentId = "a1"), local("y", assessmentId = "a2"))
        val result = plan(emptyList(), rows, inScope = { it.fields["assessmentId"] == "a1" })
        assertThat(result.deleteIds).containsExactly("x")
    }

    @Test
    fun `an unreadable document is neither stored nor taken as a deletion`() {
        val result = InboundMerge.plan(
            server = listOf(ServerDoc<Mark>("broken", row = null)),
            candidates = listOf(local("broken")),
            dirtyIds = emptySet(),
            inScope = { true },
            coverage = Coverage.IdRange(null, null),
        )
        assertThat(result.upserts).isEmpty()
        assertThat(result.deleteIds).isEmpty()
    }

    @Test
    fun `ids are ordered by UTF-8 bytes, as Firestore and SQLite order them`() {
        assertThat(FirestoreIdOrder.compare("A", "a")).isLessThan(0)
        assertThat(FirestoreIdOrder.compare("a", "aa")).isLessThan(0)
        assertThat(FirestoreIdOrder.compare("s_10", "s_9")).isLessThan(0)
        // UTF-16 (String.compareTo) puts the emoji first; UTF-8 bytes put it after U+FF61.
        val halfwidth = "｡"
        val emoji = "😀"
        assertThat(halfwidth.compareTo(emoji)).isGreaterThan(0)
        assertThat(FirestoreIdOrder.compare(halfwidth, emoji)).isLessThan(0)
        assertThat(InboundMerge.covers(Coverage.IdRange(halfwidth, null), emoji)).isTrue()
    }

    // ---- When and how downloads run ----

    private val hour = 60L * 60 * 1000
    private val plain = RemoteQuery(SyncCollection.SCORES)
    private val incremental = RemoteQuery(SyncCollection.SNAPSHOTS, incremental = true)

    @Test
    fun `collections without a reliable updatedAt are always read in full`() {
        assertThat(InboundSchedule.mode(plain, ScanState(), 0)).isEqualTo(ScanMode.FULL)
        assertThat(InboundSchedule.mode(plain, ScanState(lastFullAtMillis = 0), 1)).isEqualTo(ScanMode.FULL)
    }

    @Test
    fun `incremental collections download changes, with a full pass every day for deletions`() {
        val now = 100 * hour
        assertThat(InboundSchedule.mode(incremental, ScanState(), now)).isEqualTo(ScanMode.CHANGES)
        assertThat(InboundSchedule.mode(incremental, ScanState(lastFullAtMillis = now - hour), now)).isEqualTo(ScanMode.CHANGES)
        assertThat(InboundSchedule.mode(incremental, ScanState(lastFullAtMillis = now - 25 * hour), now)).isEqualTo(ScanMode.FULL)
        // A full pass under way is finished first.
        assertThat(InboundSchedule.mode(incremental, ScanState(idCursor = "m", lastFullAtMillis = now), now)).isEqualTo(ScanMode.FULL)
        // A clock that went backwards counts as due.
        assertThat(InboundSchedule.mode(incremental, ScanState(lastFullAtMillis = now + hour), now)).isEqualTo(ScanMode.FULL)
    }

    @Test
    fun `cursors move page by page and reset when a pass ends`() {
        val midPass = InboundSchedule.afterFullPage(ScanState(), "m", 5)
        assertThat(midPass).isEqualTo(ScanState(idCursor = "m"))
        assertThat(InboundSchedule.afterFullPage(midPass, null, 9)).isEqualTo(ScanState(lastFullAtMillis = 9))

        val cursor = ChangesCursor(10, 5, "doc")
        val changed = InboundSchedule.afterChangesPage(ScanState(), cursor, 5)
        assertThat(changed).isEqualTo(ScanState(changesCursor = cursor))
        // The first changes download to reach the end counts as a full pass.
        assertThat(InboundSchedule.afterChangesPage(changed, null, 7)).isEqualTo(ScanState(lastFullAtMillis = 7, changesCursor = cursor))
        assertThat(InboundSchedule.afterChangesPage(ScanState(lastFullAtMillis = 1), null, 7)).isEqualTo(ScanState(lastFullAtMillis = 1))
    }

    @Test
    fun `opening a screen downloads at most hourly`() {
        val now = 10 * hour
        assertThat(InboundSchedule.shouldRunAutomatically(null, now)).isTrue()
        assertThat(InboundSchedule.shouldRunAutomatically(now - 10 * 60 * 1000, now)).isFalse()
        assertThat(InboundSchedule.shouldRunAutomatically(now - hour, now)).isTrue()
        assertThat(InboundSchedule.shouldRunAutomatically(now + hour, now)).isTrue()
    }

    @Test
    fun `a stored position survives encoding, including odd ids`() {
        val states = listOf(
            ScanState(),
            ScanState(idCursor = "a|b%7C|c%"),
            ScanState(lastFullAtMillis = 123, changesCursor = ChangesCursor(1_700_000_000, 999_999, "x|y")),
            ScanState(idCursor = "z", lastFullAtMillis = 1, changesCursor = ChangesCursor(0, 0, "%")),
        )
        states.forEach { assertThat(ScanStateCodec.decode(ScanStateCodec.encode(it))).isEqualTo(it) }
        assertThat(ScanStateCodec.decode("garbage")).isEqualTo(ScanState())
        assertThat(ScanStateCodec.decode(null)).isEqualTo(ScanState())
    }
}
