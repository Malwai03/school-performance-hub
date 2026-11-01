package com.schoolapp.data.sync

import com.google.common.truth.Truth.assertThat
import com.schoolapp.core.logic.FirestoreIdOrder
import com.schoolapp.core.logic.InboundMerge
import com.schoolapp.core.logic.InboundScope
import com.schoolapp.core.logic.LocalRow
import com.schoolapp.core.logic.RemoteQuery
import com.schoolapp.core.logic.ScanMode
import com.schoolapp.core.logic.SyncCollection
import com.schoolapp.core.logic.SyncLimits
import com.schoolapp.core.model.ResultSnapshot
import com.schoolapp.core.model.SchoolUser
import com.schoolapp.core.model.UserRole
import com.schoolapp.data.local.entity.AssessmentCycleEntity
import com.schoolapp.data.local.entity.AssessmentEntity
import com.schoolapp.data.local.entity.ScoreEntity
import com.schoolapp.data.local.entity.SectionEntity
import com.schoolapp.data.local.entity.StudentEntity
import com.schoolapp.data.local.entity.SubjectEntity
import com.schoolapp.data.local.entity.TeacherAssignmentEntity
import com.schoolapp.data.local.entity.toDomain
import com.schoolapp.domain.repository.SyncOutcome
import com.schoolapp.domain.repository.SyncTrigger
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InboundSyncEngineTest {

    private val school = "school_A"
    private val admin = SchoolUser("admin-1", school, UserRole.ADMIN, "Admin", "A1", true)
    private val teacher = SchoolUser("teacher-1", school, UserRole.TEACHER, "Maya", "T1", true)
    private val student = SchoolUser("s1", school, UserRole.STUDENT, "Rohan", "S1", true)

    private val hour = 60L * 60 * 1000

    // ---------------------------------------------------------------- fakes

    /** Firestore, as far as the download sees it: filters, id or updatedAt order, startAfter, limit. */
    private class FakeServer {
        private val docs = mutableMapOf<SyncCollection, MutableMap<String, Pair<Map<String, Any?>, ServerTime>>>()
        private var serverClock = 1_000L
        val requests = mutableListOf<FetchRequest>()
        val served = mutableListOf<Pair<SyncCollection, String>>()
        var failOn: SyncCollection? = null
        var beforeAnswer: (FetchRequest) -> Unit = {}

        fun put(collection: SyncCollection, id: String, data: Map<String, Any?>) {
            serverClock++
            docs.getOrPut(collection) { mutableMapOf() }[id] = data to ServerTime(serverClock, 0)
        }

        fun delete(collection: SyncCollection, id: String) {
            docs[collection]?.remove(id)
        }

        val remote = InboundRemote { request ->
            requests += request
            if (request.query.collection == failOn) error("UNAVAILABLE")
            beforeAnswer(request)
            val q = request.query
            val matching = docs[q.collection].orEmpty().filter { (id, entry) -> matches(q, id, entry.first) }
            val page = when (request.mode) {
                ScanMode.FULL -> matching.keys.sortedWith(FirestoreIdOrder)
                    .filter { request.afterId == null || FirestoreIdOrder.compare(it, request.afterId) > 0 }
                    .map { RemoteDoc(it, matching.getValue(it).first, updatedAt = matching.getValue(it).second) }
                ScanMode.CHANGES -> matching.entries
                    .sortedWith(compareBy<Map.Entry<String, Pair<Map<String, Any?>, ServerTime>>> { it.value.second.seconds }.thenBy(FirestoreIdOrder) { it.key })
                    .filter { entry ->
                        val after = request.afterChange
                        after == null || entry.value.second.seconds > after.seconds ||
                            (entry.value.second.seconds == after.seconds && FirestoreIdOrder.compare(entry.key, after.docId) > 0)
                    }
                    .map { RemoteDoc(it.key, it.value.first, updatedAt = it.value.second) }
            }.take(request.limit)
            served += page.map { q.collection to it.id }
            page
        }

        private fun matches(q: RemoteQuery, id: String, data: Map<String, Any?>): Boolean {
            fun same(a: Any?, b: Any) = if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
            if (q.docIds != null && id !in q.docIds!!) return false
            if (q.equalTo.any { (field, value) -> !same(data[field], value) }) return false
            if (q.inField != null && q.inValues.none { same(data[q.inField], it) }) return false
            return true
        }
    }

    /** Room, as far as the download sees it. */
    private class FakeDevice : InboundLocal {
        val tables = mutableMapOf<SyncCollection, MutableMap<String, Any>>()
        var writes = 0

        fun ids(collection: SyncCollection): Set<String> = tables[collection].orEmpty().keys

        fun <T> get(collection: SyncCollection, id: String): T? {
            @Suppress("UNCHECKED_CAST")
            return tables[collection]?.get(id) as T?
        }

        fun put(collection: SyncCollection, id: String, row: Any) {
            tables.getOrPut(collection) { mutableMapOf() }[id] = row
        }

        override suspend fun facts(user: SchoolUser): InboundScope.ScopeFacts = when (user.role) {
            UserRole.ADMIN -> InboundScope.ScopeFacts()
            UserRole.TEACHER -> InboundScope.ScopeFacts(
                assignments = tables[SyncCollection.ASSIGNMENTS].orEmpty().values
                    .map { (it as TeacherAssignmentEntity).toDomain() }.filter { it.teacherUid == user.uid },
                assessments = tables[SyncCollection.ASSESSMENTS].orEmpty().values.map { (it as AssessmentEntity).toDomain() },
            )
            UserRole.STUDENT -> {
                val own = get<StudentEntity>(SyncCollection.STUDENTS, user.uid)
                InboundScope.ScopeFacts(
                    ownStudent = own?.toDomain(),
                    ownSection = own?.let { get<SectionEntity>(SyncCollection.SECTIONS, it.sectionId) }?.toDomain(),
                )
            }
        }

        override suspend fun withPage(
            collection: SyncCollection,
            selection: RowSelection,
            stillValid: suspend () -> Boolean,
            plan: (candidates: List<LocalRow>, dirty: List<LocalRow>) -> InboundMerge.Plan<Any>,
        ): Boolean {
            if (!stillValid()) return false
            val table = tables.getOrPut(collection) { mutableMapOf() }
            val all = table.values.map { localRow(it) }
            val candidates = when (selection) {
                RowSelection.None -> emptyList()
                RowSelection.All -> all
                is RowSelection.Ids -> all.filter { it.id in selection.ids }
                is RowSelection.IdRange -> all.filter {
                    (selection.afterId == null || FirestoreIdOrder.compare(it.id, selection.afterId) > 0) &&
                        (selection.throughId == null || FirestoreIdOrder.compare(it.id, selection.throughId) <= 0)
                }
            }
            val decided = plan(candidates, all.filter { it.dirty })
            decided.upserts.forEach { table[idOf(it)] = it }
            decided.deleteIds.forEach { table.remove(it) }
            writes++
            return true
        }

        private fun localRow(row: Any): LocalRow = when (row) {
            is SectionEntity -> InboundDocs.localRow(row)
            is SubjectEntity -> InboundDocs.localRow(row)
            is StudentEntity -> InboundDocs.localRow(row)
            is AssessmentCycleEntity -> InboundDocs.localRow(row)
            is AssessmentEntity -> InboundDocs.localRow(row)
            is TeacherAssignmentEntity -> InboundDocs.localRow(row)
            is ScoreEntity -> InboundDocs.localRow(row)
            is ResultSnapshot -> LocalRow(row.snapshotId, mapOf("studentId" to row.studentId, "cycleId" to row.cycleId))
            else -> error("unknown row $row")
        }

        private fun idOf(row: Any): String = localRow(row).id
    }

    private class FakeStateStore : InboundStateStore {
        val byOwner = mutableMapOf<String, InboundSyncState>()
        override suspend fun read(owner: String) = byOwner[owner] ?: InboundSyncState()
        override suspend fun write(owner: String, state: InboundSyncState) {
            byOwner[owner] = state
        }
    }

    private val server = FakeServer()
    private val tracker = LocalChangeTracker()
    private val states = FakeStateStore()
    private var clock = 100 * hour

    private fun engine(device: FakeDevice, limits: SyncLimits = SyncLimits()) =
        InboundSyncEngine(server.remote, device, states, tracker, limits, now = { clock })

    private suspend fun InboundSyncEngine.sync(
        user: SchoolUser,
        trigger: SyncTrigger = SyncTrigger.MANUAL,
        stillValid: suspend () -> Boolean = { true },
    ) = run(user, school, trigger, stillValid)

    // ---------------------------------------------------------------- server content (as the app and backend write it)

    private fun section(id: String, grade: Int) =
        server.put(SyncCollection.SECTIONS, id, mapOf("sectionId" to id, "schoolId" to school, "grade" to grade.toLong(), "sectionName" to id))

    private fun subject(id: String, grade: Int) =
        server.put(SyncCollection.SUBJECTS, id, mapOf("subjectId" to id, "grade" to grade.toLong(), "name" to id))

    private fun studentDoc(id: String, sectionId: String, name: String = id) =
        server.put(SyncCollection.STUDENTS, id, mapOf("studentId" to id, "sectionId" to sectionId, "rollNumber" to 1L, "fullName" to name))

    private fun cycle(id: String, published: Boolean = false) =
        server.put(SyncCollection.CYCLES, id, mapOf("cycleId" to id, "name" to id, "published" to published))

    private fun assessmentDoc(id: String, sectionId: String, subjectId: String) =
        server.put(SyncCollection.ASSESSMENTS, id, mapOf("cycleId" to "c1", "sectionId" to sectionId, "subjectId" to subjectId, "maxMarks" to 100L))

    private fun assignmentDoc(sectionId: String, subjectId: String, uid: String) {
        val id = "${sectionId}_${subjectId}_$uid"
        server.put(SyncCollection.ASSIGNMENTS, id, mapOf("sectionId" to sectionId, "subjectId" to subjectId, "teacherUid" to uid))
    }

    private fun scoreDoc(assessmentId: String, studentId: String, marks: Double) =
        server.put(SyncCollection.SCORES, "${assessmentId}_$studentId", mapOf("assessmentId" to assessmentId, "studentId" to studentId, "obtainedMarks" to marks, "status" to "PRESENT"))

    private fun snapshotDoc(id: String, studentId: String, overall: Double) =
        server.put(SyncCollection.SNAPSHOTS, id, mapOf("cycleId" to "c1", "sectionId" to "g6_a", "studentId" to studentId, "overallPercentage" to overall, "passStatus" to "PASS", "riskLevel" to "LOW"))

    /** What an admin on device A has built: two sections, rosters, assessments, marks, results. */
    private fun schoolBuiltOnDeviceA() {
        section("g6_a", 6); section("g7_b", 7)
        subject("math6", 6); subject("sci6", 6); subject("math7", 7)
        studentDoc("s1", "g6_a"); studentDoc("s2", "g6_a"); studentDoc("s3", "g7_b")
        cycle("c1")
        assessmentDoc("a_math", "g6_a", "math6"); assessmentDoc("a_sci", "g6_a", "sci6"); assessmentDoc("a7", "g7_b", "math7")
        assignmentDoc("g6_a", "math6", teacher.uid); assignmentDoc("g7_b", "math7", "teacher-2")
        scoreDoc("a_math", "s1", 60.0); scoreDoc("a_math", "s2", 70.0); scoreDoc("a_sci", "s1", 55.0); scoreDoc("a7", "s3", 80.0)
        snapshotDoc("c1_g6_a_s1", "s1", 61.0)
    }

    private fun score(device: FakeDevice, id: String) = device.get<ScoreEntity>(SyncCollection.SCORES, id)

    // ---------------------------------------------------------------- tests

    @Test
    fun `a teacher's fresh device gets the real section, roster, assessments and marks, and only their own`() = runTest {
        schoolBuiltOnDeviceA()
        val deviceB = FakeDevice()

        val report = engine(deviceB).sync(teacher, SyncTrigger.APP_OPEN)

        assertThat(report.outcome).isEqualTo(SyncOutcome.DONE)
        assertThat(deviceB.ids(SyncCollection.ASSIGNMENTS)).containsExactly("g6_a_math6_teacher-1")
        assertThat(deviceB.ids(SyncCollection.SECTIONS)).containsExactly("g6_a")
        assertThat(deviceB.ids(SyncCollection.SUBJECTS)).containsExactly("math6")
        assertThat(deviceB.ids(SyncCollection.STUDENTS)).containsExactly("s1", "s2")
        assertThat(deviceB.ids(SyncCollection.CYCLES)).containsExactly("c1")
        assertThat(deviceB.ids(SyncCollection.ASSESSMENTS)).containsExactly("a_math", "a_sci")
        // Marks only for the assigned section + subject; never another teacher's class.
        assertThat(deviceB.ids(SyncCollection.SCORES)).containsExactly("a_math_s1", "a_math_s2")
        assertThat(score(deviceB, "a_math_s1")!!.synced).isTrue()
        assertThat(deviceB.ids(SyncCollection.SNAPSHOTS)).isEmpty()
        assertThat(server.served.map { it.first }).doesNotContain(SyncCollection.SNAPSHOTS)
    }

    @Test
    fun `later updates and deletions arrive, and a mark not uploaded yet is kept`() = runTest {
        schoolBuiltOnDeviceA()
        val device = FakeDevice()
        val sync = engine(device)
        sync.sync(teacher)

        // On the device: s1's math mark edited offline, not uploaded yet.
        device.put(SyncCollection.SCORES, "a_math_s1", ScoreEntity("a_math_s1", "a_math", "s1", 90.0, "PRESENT", synced = false))
        // On the server: s1's mark corrected by someone else, s2's mark deleted, s2 renamed, s4 joins.
        scoreDoc("a_math", "s1", 75.0)
        server.delete(SyncCollection.SCORES, "a_math_s2")
        studentDoc("s2", "g6_a", name = "Aisha Noor")
        studentDoc("s4", "g6_a")

        val report = sync.sync(teacher)

        assertThat(report.outcome).isEqualTo(SyncOutcome.DONE)
        assertThat(score(device, "a_math_s1")).isEqualTo(ScoreEntity("a_math_s1", "a_math", "s1", 90.0, "PRESENT", synced = false))
        assertThat(report.keptLocal).isAtLeast(1)
        assertThat(device.ids(SyncCollection.SCORES)).containsExactly("a_math_s1")
        assertThat(device.get<StudentEntity>(SyncCollection.STUDENTS, "s2")!!.fullName).isEqualTo("Aisha Noor")
        assertThat(device.ids(SyncCollection.STUDENTS)).containsExactly("s1", "s2", "s4")
    }

    @Test
    fun `an unsynced mark the server deleted is kept for upload`() = runTest {
        schoolBuiltOnDeviceA()
        val device = FakeDevice()
        val sync = engine(device)
        sync.sync(teacher)
        device.put(SyncCollection.SCORES, "a_math_s2", ScoreEntity("a_math_s2", "a_math", "s2", 71.0, "ABSENT", synced = false))
        server.delete(SyncCollection.SCORES, "a_math_s2")

        sync.sync(teacher)

        assertThat(score(device, "a_math_s2")!!.synced).isFalse()
    }

    @Test
    fun `a mark saved on this device while a page was being fetched is not overwritten by that page`() = runTest {
        schoolBuiltOnDeviceA()
        val device = FakeDevice()
        val sync = engine(device)
        sync.sync(teacher)
        scoreDoc("a_math", "s1", 61.0) // the server copy the next page will carry
        server.beforeAnswer = { request ->
            if (request.query.collection == SyncCollection.SCORES) {
                // Saved and uploaded meanwhile: synced again in Room, but newer than the page.
                device.put(SyncCollection.SCORES, "a_math_s1", ScoreEntity("a_math_s1", "a_math", "s1", 95.0, "PRESENT", synced = true))
                tracker.touch(SyncCollection.SCORES, listOf("a_math_s1"))
            }
        }

        sync.sync(teacher)

        assertThat(score(device, "a_math_s1")!!.obtainedMarks).isEqualTo(95.0)
    }

    @Test
    fun `a master-data change still uploading is not overwritten or deleted`() = runTest {
        schoolBuiltOnDeviceA()
        val device = FakeDevice()
        val sync = engine(device)
        sync.sync(admin)
        device.put(SyncCollection.STUDENTS, "s9", StudentEntity("s9", school, "g6_a", 9, "New", true))
        device.put(SyncCollection.STUDENTS, "s1", StudentEntity("s1", school, "g6_a", 1, "Renamed locally", true))

        tracker.tracking(listOf(SyncCollection.STUDENTS to "s9", SyncCollection.STUDENTS to "s1")) {
            sync.sync(admin)
        }

        assertThat(device.ids(SyncCollection.STUDENTS)).contains("s9")
        assertThat(device.get<StudentEntity>(SyncCollection.STUDENTS, "s1")!!.fullName).isEqualTo("Renamed locally")
        // Once it is done, the server's version counts again.
        sync.sync(admin)
        assertThat(device.ids(SyncCollection.STUDENTS)).doesNotContain("s9")
        assertThat(device.get<StudentEntity>(SyncCollection.STUDENTS, "s1")!!.fullName).isEqualTo("s1")
    }

    @Test
    fun `a sign-out or account switch mid-run stops it before the next write`() = runTest {
        schoolBuiltOnDeviceA()
        val device = FakeDevice()
        var checks = 0

        val report = engine(device).sync(admin, stillValid = { ++checks <= 2 })

        assertThat(report.outcome).isEqualTo(SyncOutcome.ACCOUNT_CHANGED)
        assertThat(device.writes).isEqualTo(2)
        assertThat(device.ids(SyncCollection.SCORES)).isEmpty()
    }

    @Test
    fun `positions are kept per account and school`() = runTest {
        schoolBuiltOnDeviceA()
        engine(FakeDevice()).sync(admin)
        assertThat(states.byOwner.keys).containsExactly("admin-1@school_A")
        // Another account starts from scratch.
        engine(FakeDevice()).sync(teacher)
        assertThat(states.byOwner.getValue("teacher-1@school_A").scans.keys.none { it.startsWith("resultSnapshots") }).isTrue()
    }

    @Test
    fun `a large school downloads over several bounded runs, then stays in step`() = runTest {
        schoolBuiltOnDeviceA()
        (1..9).forEach { scoreDoc("a_math", "x$it", it.toDouble()) }
        val device = FakeDevice()
        val sync = engine(device, SyncLimits(pageSize = 2, maxDocsPerQueryPerRun = 4, maxDocsPerRun = 60))

        val outcomes = mutableListOf<SyncOutcome>()
        repeat(10) { if (outcomes.lastOrNull() != SyncOutcome.DONE) outcomes += sync.sync(admin).outcome }

        assertThat(outcomes.first()).isEqualTo(SyncOutcome.PARTIAL)
        assertThat(outcomes.last()).isEqualTo(SyncOutcome.DONE)
        // No request ever asked for more than a page.
        assertThat(server.requests.maxOf { it.limit }).isAtMost(2)
        assertThat(device.ids(SyncCollection.SCORES)).hasSize(13)

        server.delete(SyncCollection.SCORES, "a_math_x5")
        do { val outcome = sync.sync(admin).outcome } while (outcome != SyncOutcome.DONE)
        assertThat(device.ids(SyncCollection.SCORES)).hasSize(12)
        assertThat(device.ids(SyncCollection.SCORES)).doesNotContain("a_math_x5")
    }

    @Test
    fun `an admin's results come by updatedAt, with a daily full pass for deletions`() = runTest {
        schoolBuiltOnDeviceA()
        snapshotDoc("c1_g6_a_s2", "s2", 70.0)
        val device = FakeDevice()
        val sync = engine(device)
        sync.sync(admin)
        assertThat(device.ids(SyncCollection.SNAPSHOTS)).containsExactly("c1_g6_a_s1", "c1_g6_a_s2")

        // A correction: the next run downloads just that one result.
        snapshotDoc("c1_g6_a_s1", "s1", 64.0)
        server.served.clear()
        sync.sync(admin)
        assertThat(server.served.filter { it.first == SyncCollection.SNAPSHOTS }.map { it.second }).containsExactly("c1_g6_a_s1")
        assertThat(device.get<ResultSnapshot>(SyncCollection.SNAPSHOTS, "c1_g6_a_s1")!!.overallPercentage).isEqualTo(64.0)

        // A deleted result is only noticed by the full pass, at most a day later.
        server.delete(SyncCollection.SNAPSHOTS, "c1_g6_a_s2")
        clock += hour
        sync.sync(admin)
        assertThat(device.ids(SyncCollection.SNAPSHOTS)).contains("c1_g6_a_s2")
        clock += 24 * hour
        sync.sync(admin)
        assertThat(device.ids(SyncCollection.SNAPSHOTS)).containsExactly("c1_g6_a_s1")
    }

    @Test
    fun `opening a screen downloads at most hourly, pull-to-refresh always`() = runTest {
        schoolBuiltOnDeviceA()
        val sync = engine(FakeDevice())
        assertThat(sync.sync(teacher, SyncTrigger.APP_OPEN).outcome).isEqualTo(SyncOutcome.DONE)
        val requests = server.requests.size

        clock += 10 * 60 * 1000
        assertThat(sync.sync(teacher, SyncTrigger.APP_OPEN).outcome).isEqualTo(SyncOutcome.SKIPPED)
        assertThat(server.requests.size).isEqualTo(requests)
        assertThat(sync.sync(teacher, SyncTrigger.MANUAL).outcome).isEqualTo(SyncOutcome.DONE)
        assertThat(sync.sync(teacher, SyncTrigger.PERIODIC).outcome).isEqualTo(SyncOutcome.DONE)

        clock += 2 * hour
        assertThat(sync.sync(teacher, SyncTrigger.APP_OPEN).outcome).isEqualTo(SyncOutcome.DONE)
    }

    @Test
    fun `a teacher moved to another class loses the old class's synced data but keeps unsent marks`() = runTest {
        schoolBuiltOnDeviceA()
        val device = FakeDevice()
        val sync = engine(device)
        sync.sync(teacher)
        device.put(SyncCollection.SCORES, "a_math_s2", ScoreEntity("a_math_s2", "a_math", "s2", 88.0, "PRESENT", synced = false))

        server.delete(SyncCollection.ASSIGNMENTS, "g6_a_math6_teacher-1")
        assignmentDoc("g7_b", "math7", teacher.uid)
        sync.sync(teacher)

        assertThat(device.ids(SyncCollection.SECTIONS)).containsExactly("g7_b")
        assertThat(device.ids(SyncCollection.STUDENTS)).containsExactly("s3")
        assertThat(device.ids(SyncCollection.ASSESSMENTS)).containsExactly("a7")
        assertThat(device.ids(SyncCollection.SCORES)).containsExactly("a7_s3", "a_math_s2")
        assertThat(score(device, "a_math_s2")!!.synced).isFalse()
    }

    @Test
    fun `a student's device gets their own record and class details, never classmates or marks`() = runTest {
        schoolBuiltOnDeviceA()
        val device = FakeDevice()

        val report = engine(device).sync(student)

        assertThat(report.outcome).isEqualTo(SyncOutcome.DONE)
        assertThat(device.ids(SyncCollection.STUDENTS)).containsExactly("s1")
        assertThat(device.ids(SyncCollection.SECTIONS)).containsExactly("g6_a")
        assertThat(device.ids(SyncCollection.SUBJECTS)).containsExactly("math6", "sci6")
        assertThat(device.ids(SyncCollection.CYCLES)).containsExactly("c1")
        assertThat(server.requests.map { it.query.collection }.toSet())
            .containsExactly(SyncCollection.STUDENTS, SyncCollection.SECTIONS, SyncCollection.SUBJECTS, SyncCollection.CYCLES)
    }

    @Test
    fun `when the server cannot be reached, what was already downloaded stays`() = runTest {
        schoolBuiltOnDeviceA()
        val device = FakeDevice()
        server.failOn = SyncCollection.SCORES

        val failure = runCatching { engine(device).sync(admin) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(device.ids(SyncCollection.STUDENTS)).containsExactly("s1", "s2", "s3")
        assertThat(device.ids(SyncCollection.SCORES)).isEmpty()
        // Not counted as a run, so opening a screen tries again straight away.
        assertThat(states.byOwner.getValue("admin-1@school_A").lastRunAtMillis).isNull()
    }
}
