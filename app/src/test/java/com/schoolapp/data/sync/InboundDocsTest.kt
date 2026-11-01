package com.schoolapp.data.sync

import com.google.common.truth.Truth.assertThat
import com.schoolapp.core.logic.SyncCollection
import com.schoolapp.core.model.PassStatus
import com.schoolapp.data.local.entity.ScoreEntity
import com.schoolapp.data.local.entity.SectionEntity
import com.schoolapp.data.local.entity.StudentEntity
import org.junit.Test

class InboundDocsTest {

    @Test
    fun `the school comes from the document path, not a field an import may have left blank`() {
        val section = InboundDocs.parse(
            SyncCollection.SECTIONS, "school_A", "g6_a",
            mapOf("schoolId" to "", "grade" to 6L, "sectionName" to "A"),
        )
        assertThat(section).isEqualTo(SectionEntity("g6_a", "school_A", 6, "A"))
        val student = InboundDocs.parse(
            SyncCollection.STUDENTS, "school_A", "s1",
            mapOf("schoolId" to "school_B", "sectionId" to "g6_a", "rollNumber" to 3L, "fullName" to "Aisha"),
        )
        assertThat(student).isEqualTo(StudentEntity("s1", "school_A", "g6_a", 3, "Aisha", true))
    }

    @Test
    fun `a document missing what the app needs is unreadable`() {
        assertThat(InboundDocs.parse(SyncCollection.SECTIONS, "s", "x", mapOf("sectionName" to "A"))).isNull()
        assertThat(InboundDocs.parse(SyncCollection.STUDENTS, "s", "x", mapOf("sectionId" to " ", "fullName" to "N"))).isNull()
        assertThat(InboundDocs.parse(SyncCollection.ASSESSMENTS, "s", "x", mapOf("cycleId" to "c", "sectionId" to "g", "subjectId" to "m", "maxMarks" to 0L))).isNull()
        assertThat(InboundDocs.parse(SyncCollection.ASSIGNMENTS, "s", "x", mapOf("sectionId" to "g", "subjectId" to "m"))).isNull()
        assertThat(InboundDocs.parse(SyncCollection.SCORES, "s", "x", mapOf("assessmentId" to "a", "studentId" to "st", "obtainedMarks" to 5L, "status" to "LATE"))).isNull()
        assertThat(InboundDocs.parse(SyncCollection.SNAPSHOTS, "s", "x", mapOf("cycleId" to "c", "sectionId" to "g", "studentId" to "st", "passStatus" to "MAYBE"))).isNull()
    }

    @Test
    fun `a downloaded mark is the server's version, so it is stored as synced`() {
        val score = InboundDocs.parse(
            SyncCollection.SCORES, "s", "a1_st",
            mapOf("assessmentId" to "a1", "studentId" to "st", "obtainedMarks" to 42L),
        )
        assertThat(score).isEqualTo(ScoreEntity("a1_st", "a1", "st", 42.0, "PRESENT", synced = true))
    }

    @Test
    fun `a result snapshot keeps its subject percentages`() {
        val snapshot = InboundDocs.snapshot(
            "c1_g6_a_st",
            mapOf(
                "cycleId" to "c1", "sectionId" to "g6_a", "studentId" to "st",
                "subjectPercentages" to mapOf("math" to 81L, "sci" to 64.5, "bad" to "x"),
                "overallPercentage" to 72.75, "passStatus" to "PASS", "riskLevel" to "LOW",
            ),
        )!!
        assertThat(snapshot.subjectPercentages).containsExactly("math", 81.0, "sci", 64.5)
        assertThat(snapshot.passStatus).isEqualTo(PassStatus.PASS)
    }

    @Test
    fun `an unsynced local mark counts as a local change and clashes by student and assessment`() {
        val row = InboundDocs.localRow(ScoreEntity("id", "a1", "st", 5.0, "PRESENT", synced = false))
        assertThat(row.dirty).isTrue()
        assertThat(row.conflictKey).isEqualTo(InboundDocs.scoreConflictKey("a1", "st"))
        assertThat(InboundDocs.localRow(ScoreEntity("id", "a1", "st", 5.0, "PRESENT", synced = true)).dirty).isFalse()
    }
}
