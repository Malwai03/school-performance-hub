package com.schoolapp.data.sync

import com.schoolapp.core.logic.LocalRow
import com.schoolapp.core.logic.SyncCollection
import com.schoolapp.core.model.PassStatus
import com.schoolapp.core.model.ResultSnapshot
import com.schoolapp.core.model.RiskLevel
import com.schoolapp.core.model.ScoreStatus
import com.schoolapp.data.local.entity.AssessmentCycleEntity
import com.schoolapp.data.local.entity.AssessmentEntity
import com.schoolapp.data.local.entity.ResultSnapshotEntity
import com.schoolapp.data.local.entity.ScoreEntity
import com.schoolapp.data.local.entity.SectionEntity
import com.schoolapp.data.local.entity.StudentEntity
import com.schoolapp.data.local.entity.SubjectEntity
import com.schoolapp.data.local.entity.TeacherAssignmentEntity

/**
 * Turns downloaded Firestore documents into Room rows, and Room rows into [LocalRow]s for the
 * merge. Plain maps in, plain objects out, so it is tested on the JVM.
 *
 * The school always comes from the document's path (schools/{schoolId}/...), never from a
 * schoolId field inside it, which imports may leave blank. A document missing what the app
 * needs to show it returns null: it is ignored and any local copy is left alone.
 */
internal object InboundDocs {

    fun parse(collection: SyncCollection, schoolId: String, id: String, data: Map<String, Any?>): Any? =
        runCatching {
            when (collection) {
                SyncCollection.SECTIONS -> section(schoolId, id, data)
                SyncCollection.SUBJECTS -> subject(schoolId, id, data)
                SyncCollection.STUDENTS -> student(schoolId, id, data)
                SyncCollection.CYCLES -> cycle(schoolId, id, data)
                SyncCollection.ASSESSMENTS -> assessment(id, data)
                SyncCollection.ASSIGNMENTS -> assignment(schoolId, id, data)
                SyncCollection.SCORES -> score(id, data)
                SyncCollection.SNAPSHOTS -> snapshot(id, data)
            }
        }.getOrNull()

    fun section(schoolId: String, id: String, d: Map<String, Any?>): SectionEntity? {
        val grade = d.int("grade") ?: return null
        return SectionEntity(id, schoolId, grade, d.text("sectionName") ?: return null)
    }

    fun subject(schoolId: String, id: String, d: Map<String, Any?>): SubjectEntity? {
        val grade = d.int("grade") ?: return null
        return SubjectEntity(
            subjectId = id,
            schoolId = schoolId,
            grade = grade,
            name = d.text("name") ?: return null,
            passThresholdPct = d.double("passThresholdPct") ?: 33.0,
            mandatory = d.bool("mandatory") ?: true,
        )
    }

    fun student(schoolId: String, id: String, d: Map<String, Any?>): StudentEntity? {
        return StudentEntity(
            studentId = id,
            schoolId = schoolId,
            sectionId = d.text("sectionId") ?: return null,
            rollNumber = d.int("rollNumber") ?: 0,
            fullName = d.text("fullName") ?: return null,
            active = d.bool("active") ?: true,
        )
    }

    fun cycle(schoolId: String, id: String, d: Map<String, Any?>): AssessmentCycleEntity? = AssessmentCycleEntity(
        cycleId = id,
        schoolId = schoolId,
        name = d.text("name") ?: id,
        startDateIso = d.string("startDateIso").orEmpty(),
        endDateIso = d.string("endDateIso").orEmpty(),
        published = d.bool("published") ?: false,
    )

    fun assessment(id: String, d: Map<String, Any?>): AssessmentEntity? {
        val maxMarks = d.double("maxMarks")?.takeIf { it > 0 } ?: return null
        return AssessmentEntity(
            assessmentId = id,
            cycleId = d.text("cycleId") ?: return null,
            sectionId = d.text("sectionId") ?: return null,
            subjectId = d.text("subjectId") ?: return null,
            title = d.text("title") ?: id,
            maxMarks = maxMarks,
            weight = d.double("weight") ?: 1.0,
        )
    }

    fun assignment(schoolId: String, id: String, d: Map<String, Any?>): TeacherAssignmentEntity? {
        return TeacherAssignmentEntity(
            assignmentId = id,
            schoolId = schoolId,
            sectionId = d.text("sectionId") ?: return null,
            subjectId = d.text("subjectId") ?: return null,
            teacherUid = d.text("teacherUid") ?: return null,
        )
    }

    /** A downloaded score is the server's version, so it is stored as synced. */
    fun score(id: String, d: Map<String, Any?>): ScoreEntity? {
        val status = d.string("status")?.let { runCatching { ScoreStatus.valueOf(it) }.getOrNull() }
            ?: if (d["status"] == null) ScoreStatus.PRESENT else return null
        return ScoreEntity(
            scoreId = id,
            assessmentId = d.text("assessmentId") ?: return null,
            studentId = d.text("studentId") ?: return null,
            obtainedMarks = d.double("obtainedMarks") ?: return null,
            status = status.name,
            synced = true,
        )
    }

    fun snapshot(id: String, d: Map<String, Any?>): ResultSnapshot? {
        val percentages = (d["subjectPercentages"] as? Map<*, *>)
            ?.entries
            ?.filter { it.key is String && it.value is Number }
            ?.associate { it.key as String to (it.value as Number).toDouble() }
            .orEmpty()
        return ResultSnapshot(
            snapshotId = id,
            cycleId = d.text("cycleId") ?: return null,
            sectionId = d.text("sectionId") ?: return null,
            studentId = d.text("studentId") ?: return null,
            subjectPercentages = percentages,
            overallPercentage = d.double("overallPercentage"),
            passStatus = PassStatus.valueOf(d.string("passStatus") ?: PassStatus.INCOMPLETE.name),
            riskLevel = RiskLevel.valueOf(d.string("riskLevel") ?: RiskLevel.HIGH.name),
        )
    }

    /** Score clash key: a local mark and a server mark for the same assessment + student. */
    fun scoreConflictKey(assessmentId: String, studentId: String): String = "$assessmentId|$studentId"

    // Room rows as the merge sees them. Field names are the Firestore field names the scope filters use.

    fun localRow(section: SectionEntity, dirty: Boolean = false) =
        LocalRow(section.sectionId, mapOf("grade" to section.grade), dirty)

    fun localRow(subject: SubjectEntity, dirty: Boolean = false) =
        LocalRow(subject.subjectId, mapOf("grade" to subject.grade), dirty)

    fun localRow(student: StudentEntity, dirty: Boolean = false) =
        LocalRow(student.studentId, mapOf("sectionId" to student.sectionId), dirty)

    fun localRow(cycle: AssessmentCycleEntity, dirty: Boolean = false) =
        LocalRow(cycle.cycleId, mapOf("published" to cycle.published), dirty)

    fun localRow(assessment: AssessmentEntity, dirty: Boolean = false) = LocalRow(
        assessment.assessmentId,
        mapOf("sectionId" to assessment.sectionId, "subjectId" to assessment.subjectId, "cycleId" to assessment.cycleId),
        dirty,
    )

    fun localRow(assignment: TeacherAssignmentEntity, dirty: Boolean = false) = LocalRow(
        assignment.assignmentId,
        mapOf("teacherUid" to assignment.teacherUid, "sectionId" to assignment.sectionId, "subjectId" to assignment.subjectId),
        dirty,
    )

    /** An unsynced mark is a local change the server has not confirmed. */
    fun localRow(score: ScoreEntity, dirty: Boolean = false) = LocalRow(
        score.scoreId,
        mapOf("assessmentId" to score.assessmentId, "studentId" to score.studentId),
        dirty = dirty || !score.synced,
        conflictKey = scoreConflictKey(score.assessmentId, score.studentId),
    )

    fun localRow(snapshot: ResultSnapshotEntity, dirty: Boolean = false) = LocalRow(
        snapshot.snapshotId,
        mapOf("studentId" to snapshot.studentId, "cycleId" to snapshot.cycleId, "sectionId" to snapshot.sectionId),
        dirty,
    )

    private fun Map<String, Any?>.string(key: String): String? = this[key] as? String

    /** A non-blank string. */
    private fun Map<String, Any?>.text(key: String): String? = string(key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<String, Any?>.double(key: String): Double? =
        (this[key] as? Number)?.toDouble()?.takeIf { !it.isNaN() && !it.isInfinite() }

    private fun Map<String, Any?>.int(key: String): Int? = double(key)?.toInt()

    private fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
}
