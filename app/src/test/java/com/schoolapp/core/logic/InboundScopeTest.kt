package com.schoolapp.core.logic

import com.google.common.truth.Truth.assertThat
import com.schoolapp.core.model.Assessment
import com.schoolapp.core.model.GradeSection
import com.schoolapp.core.model.SchoolUser
import com.schoolapp.core.model.Student
import com.schoolapp.core.model.TeacherAssignment
import com.schoolapp.core.model.UserRole
import org.junit.Test

class InboundScopeTest {

    private val school = "school_A"
    private val admin = SchoolUser("admin-1", school, UserRole.ADMIN, "Admin", "A1", true)
    private val teacher = SchoolUser("teacher-1", school, UserRole.TEACHER, "Maya", "T1", true)
    private val student = SchoolUser("stu-1", school, UserRole.STUDENT, "Rohan", "S1", true)

    private fun assignment(section: String, subject: String, uid: String = teacher.uid, schoolId: String = school) =
        TeacherAssignment("${section}_${subject}_$uid", schoolId, section, subject, uid)

    private fun assessment(id: String, section: String, subject: String) =
        Assessment(id, "c1", section, subject, id, 100.0, 1.0)

    private fun allPhases(user: SchoolUser, facts: InboundScope.ScopeFacts) =
        (0 until InboundScope.PHASES).flatMap { InboundScope.queries(user, it, facts) }

    @Test
    fun `an admin downloads every collection of the school, unfiltered, with results by updatedAt`() {
        val queries = allPhases(admin, InboundScope.ScopeFacts())
        assertThat(queries.map { it.collection }).containsExactlyElementsIn(SyncCollection.values()).inOrder()
        assertThat(queries.all { it.wholeCollection }).isTrue()
        assertThat(queries.filter { it.incremental }.map { it.collection }).containsExactly(SyncCollection.SNAPSHOTS)
    }

    @Test
    fun `a teacher first downloads only their own assignments`() {
        val phase0 = InboundScope.queries(teacher, 0, InboundScope.ScopeFacts())
        assertThat(phase0).containsExactly(
            RemoteQuery(SyncCollection.ASSIGNMENTS, equalTo = listOf("teacherUid" to teacher.uid)),
        )
    }

    @Test
    fun `a teacher downloads only the sections, subjects, students and assessments of their assignments`() {
        val facts = InboundScope.ScopeFacts(
            assignments = listOf(
                assignment("g6_a", "math6"),
                assignment("g6_b", "math6"),
                // Another teacher's, and an assignment in another school: never in scope.
                assignment("g7_a", "sci7", uid = "teacher-2"),
                assignment("g9_z", "eng9", schoolId = "school_B"),
            ),
        )
        val phase1 = InboundScope.queries(teacher, 1, facts)
        assertThat(phase1).containsExactly(
            RemoteQuery(SyncCollection.SECTIONS, docIds = listOf("g6_a", "g6_b")),
            RemoteQuery(SyncCollection.SUBJECTS, docIds = listOf("math6")),
            RemoteQuery(SyncCollection.CYCLES),
            RemoteQuery(SyncCollection.STUDENTS, inField = "sectionId", inValues = listOf("g6_a", "g6_b")),
            RemoteQuery(SyncCollection.ASSESSMENTS, inField = "sectionId", inValues = listOf("g6_a", "g6_b")),
        )
    }

    @Test
    fun `a teacher downloads marks only for assessments of an assigned section and subject`() {
        val facts = InboundScope.ScopeFacts(
            assignments = listOf(assignment("g6_a", "math6")),
            assessments = listOf(
                assessment("a_math", "g6_a", "math6"),
                // Same section, subject not assigned to this teacher.
                assessment("a_sci", "g6_a", "sci6"),
                assessment("a_other", "g6_b", "math6"),
            ),
        )
        assertThat(InboundScope.queries(teacher, 2, facts)).containsExactly(
            RemoteQuery(SyncCollection.SCORES, inField = "assessmentId", inValues = listOf("a_math")),
        )
    }

    @Test
    fun `a teacher with no assignments downloads only the cycles`() {
        val queries = allPhases(teacher, InboundScope.ScopeFacts()).filter { it.collection != SyncCollection.ASSIGNMENTS }
        assertThat(queries).containsExactly(RemoteQuery(SyncCollection.CYCLES))
    }

    @Test
    fun `long id lists are split at Firestore's 30-value limit`() {
        val sections = (1..65).map { "s%03d".format(it) }
        val facts = InboundScope.ScopeFacts(assignments = sections.map { assignment(it, "math") })
        val students = InboundScope.queries(teacher, 1, facts).filter { it.collection == SyncCollection.STUDENTS }
        assertThat(students.map { it.inValues.size }).containsExactly(30, 30, 5).inOrder()
        assertThat(students.flatMap { it.inValues }).containsExactlyElementsIn(sections)
    }

    @Test
    fun `a student downloads their own record, section, grade subjects and cycles, and nothing else`() {
        val own = Student(student.uid, school, "g6_a", 1, "Rohan", true)
        val facts = InboundScope.ScopeFacts(ownStudent = own, ownSection = GradeSection("g6_a", school, 6, "A"))
        assertThat(allPhases(student, facts)).containsExactly(
            RemoteQuery(SyncCollection.STUDENTS, docIds = listOf(student.uid)),
            RemoteQuery(SyncCollection.CYCLES),
            RemoteQuery(SyncCollection.SECTIONS, docIds = listOf("g6_a")),
            RemoteQuery(SyncCollection.SUBJECTS, equalTo = listOf("grade" to 6)),
        )
    }

    @Test
    fun `a student never asks for marks, assignments or other students, which the rules refuse or the app never shows`() {
        val facts = InboundScope.ScopeFacts(
            ownStudent = Student(student.uid, school, "g6_a", 1, "Rohan", true),
            ownSection = GradeSection("g6_a", school, 6, "A"),
        )
        val queries = allPhases(student, facts)
        assertThat(queries.map { it.collection }).containsNoneOf(
            SyncCollection.SCORES,
            SyncCollection.ASSIGNMENTS,
            SyncCollection.ASSESSMENTS,
            SyncCollection.SNAPSHOTS,
        )
        assertThat(queries.filter { it.collection == SyncCollection.STUDENTS }.single().docIds).containsExactly(student.uid)
        // Results are reconciled by getStudentTrend instead, so the download leaves them alone.
        assertThat(InboundScope.managedCollections(UserRole.STUDENT)).doesNotContain(SyncCollection.SNAPSHOTS)
    }

    @Test
    fun `a student's section is only looked up from their own record`() {
        val someoneElse = Student("stu-2", school, "g9_z", 1, "Other", true)
        assertThat(InboundScope.queries(student, 1, InboundScope.ScopeFacts(ownStudent = someoneElse))).isEmpty()
    }

    @Test
    fun `an inactive account downloads nothing`() {
        val inactive = teacher.copy(active = false)
        assertThat(allPhases(inactive, InboundScope.ScopeFacts(assignments = listOf(assignment("g6_a", "math6"))))).isEmpty()
    }

    @Test
    fun `rows no query covers any more are out of scope, unless they hold a local change`() {
        val queries = listOf(RemoteQuery(SyncCollection.STUDENTS, inField = "sectionId", inValues = listOf("g6_a")))
        val rows = listOf(
            LocalRow("s1", mapOf("sectionId" to "g6_a")),
            LocalRow("s2", mapOf("sectionId" to "g6_b")),
            LocalRow("s3", mapOf("sectionId" to "g6_b"), dirty = true),
        )
        assertThat(InboundScope.outOfScope(rows, queries)).containsExactly("s2")
        assertThat(InboundScope.outOfScope(rows, emptyList())).containsExactly("s1", "s2")
    }

    @Test
    fun `a query matches local rows the way Firestore compares values`() {
        val byGrade = RemoteQuery(SyncCollection.SUBJECTS, equalTo = listOf("grade" to 6))
        assertThat(byGrade.matches(LocalRow("m", mapOf("grade" to 6L)))).isTrue()
        assertThat(byGrade.matches(LocalRow("m", mapOf("grade" to 6.0)))).isTrue()
        assertThat(byGrade.matches(LocalRow("m", mapOf("grade" to 7)))).isFalse()
        val byIds = RemoteQuery(SyncCollection.SECTIONS, docIds = listOf("a"))
        assertThat(byIds.matches(LocalRow("a"))).isTrue()
        assertThat(byIds.matches(LocalRow("b"))).isFalse()
        assertThat(RemoteQuery(SyncCollection.SECTIONS).matches(LocalRow("anything"))).isTrue()
    }

    @Test
    fun `each query has its own stable cursor key`() {
        val a = RemoteQuery(SyncCollection.SCORES, inField = "assessmentId", inValues = listOf("x", "y"))
        val b = RemoteQuery(SyncCollection.SCORES, inField = "assessmentId", inValues = listOf("x", "z"))
        assertThat(a.key).isEqualTo(a.copy().key)
        assertThat(a.key).isNotEqualTo(b.key)
        assertThat(RemoteQuery(SyncCollection.SNAPSHOTS).key).isNotEqualTo(RemoteQuery(SyncCollection.SNAPSHOTS, incremental = true).key)
    }
}
