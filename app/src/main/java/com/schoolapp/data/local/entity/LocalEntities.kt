package com.schoolapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.schoolapp.core.model.Assessment
import com.schoolapp.core.model.AssessmentCycle
import com.schoolapp.core.model.GradeSection
import com.schoolapp.core.model.PassStatus
import com.schoolapp.core.model.ResultSnapshot
import com.schoolapp.core.model.RiskLevel
import com.schoolapp.core.model.SchoolUser
import com.schoolapp.core.model.ScoreEntry
import com.schoolapp.core.model.ScoreStatus
import com.schoolapp.core.model.Student
import com.schoolapp.core.model.Subject
import com.schoolapp.core.model.TeacherAssignment

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val uid: String,
    val schoolId: String,
    val role: String,
    val displayName: String,
    val code: String,
    val active: Boolean,
)

@Entity(tableName = "sections")
data class SectionEntity(
    @PrimaryKey val sectionId: String,
    val schoolId: String,
    val grade: Int,
    val sectionName: String,
)

@Entity(tableName = "subjects")
data class SubjectEntity(
    @PrimaryKey val subjectId: String,
    val schoolId: String,
    val grade: Int,
    val name: String,
    val passThresholdPct: Double,
    val mandatory: Boolean,
)

@Entity(tableName = "students")
data class StudentEntity(
    @PrimaryKey val studentId: String,
    val schoolId: String,
    val sectionId: String,
    val rollNumber: Int,
    val fullName: String,
    val active: Boolean,
)

@Entity(tableName = "assessment_cycles")
data class AssessmentCycleEntity(
    @PrimaryKey val cycleId: String,
    val schoolId: String,
    val name: String,
    val startDateIso: String,
    val endDateIso: String,
    val published: Boolean,
)

@Entity(tableName = "assessments")
data class AssessmentEntity(
    @PrimaryKey val assessmentId: String,
    val cycleId: String,
    val sectionId: String,
    val subjectId: String,
    val title: String,
    val maxMarks: Double,
    val weight: Double,
)

@Entity(tableName = "scores")
data class ScoreEntity(
    @PrimaryKey val scoreId: String,
    val assessmentId: String,
    val studentId: String,
    val obtainedMarks: Double,
    val status: String,
    val synced: Boolean,
)

@Entity(tableName = "result_snapshots")
data class ResultSnapshotEntity(
    @PrimaryKey val snapshotId: String,
    val cycleId: String,
    val sectionId: String,
    val studentId: String,
    val subjectPercentagesJson: String,
    val overallPercentage: Double?,
    val passStatus: String,
    val riskLevel: String,
)

@Entity(tableName = "teacher_assignments")
data class TeacherAssignmentEntity(
    @PrimaryKey val assignmentId: String,
    val schoolId: String,
    val sectionId: String,
    val subjectId: String,
    val teacherUid: String,
)

fun UserEntity.toDomain() = SchoolUser(
    uid = uid,
    schoolId = schoolId,
    role = com.schoolapp.core.model.UserRole.valueOf(role),
    displayName = displayName,
    code = code,
    active = active,
)

fun SchoolUser.toEntity() = UserEntity(
    uid = uid,
    schoolId = schoolId,
    role = role.name,
    displayName = displayName,
    code = code,
    active = active,
)

fun SectionEntity.toDomain() = GradeSection(sectionId, schoolId, grade, sectionName)
fun GradeSection.toEntity() = SectionEntity(sectionId, schoolId, grade, sectionName)

fun SubjectEntity.toDomain() = Subject(subjectId, schoolId, grade, name, passThresholdPct, mandatory)
fun Subject.toEntity() = SubjectEntity(subjectId, schoolId, grade, name, passThresholdPct, mandatory)

fun StudentEntity.toDomain() = Student(studentId, schoolId, sectionId, rollNumber, fullName, active)
fun Student.toEntity() = StudentEntity(studentId, schoolId, sectionId, rollNumber, fullName, active)

fun AssessmentCycleEntity.toDomain() = AssessmentCycle(cycleId, schoolId, name, startDateIso, endDateIso, published)
fun AssessmentCycle.toEntity() = AssessmentCycleEntity(cycleId, schoolId, name, startDateIso, endDateIso, published)

fun AssessmentEntity.toDomain() = Assessment(assessmentId, cycleId, sectionId, subjectId, title, maxMarks, weight)
fun Assessment.toEntity() = AssessmentEntity(assessmentId, cycleId, sectionId, subjectId, title, maxMarks, weight)

fun ScoreEntity.toDomain() = ScoreEntry(scoreId, assessmentId, studentId, obtainedMarks, ScoreStatus.valueOf(status))
fun ScoreEntry.toEntity(synced: Boolean = false) = ScoreEntity(scoreId, assessmentId, studentId, obtainedMarks, status.name, synced)

fun ResultSnapshotEntity.toDomain(subjectPercentages: Map<String, Double>) = ResultSnapshot(
    snapshotId = snapshotId,
    cycleId = cycleId,
    sectionId = sectionId,
    studentId = studentId,
    subjectPercentages = subjectPercentages,
    overallPercentage = overallPercentage,
    passStatus = PassStatus.valueOf(passStatus),
    riskLevel = RiskLevel.valueOf(riskLevel),
)

fun ResultSnapshot.toEntity(subjectPercentagesJson: String) = ResultSnapshotEntity(
    snapshotId = snapshotId,
    cycleId = cycleId,
    sectionId = sectionId,
    studentId = studentId,
    subjectPercentagesJson = subjectPercentagesJson,
    overallPercentage = overallPercentage,
    passStatus = passStatus.name,
    riskLevel = riskLevel.name,
)

fun TeacherAssignment.toEntity() = TeacherAssignmentEntity(assignmentId, schoolId, sectionId, subjectId, teacherUid)
fun TeacherAssignmentEntity.toDomain() = TeacherAssignment(assignmentId, schoolId, sectionId, subjectId, teacherUid)
