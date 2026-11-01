package com.schoolapp.core.logic

import com.schoolapp.core.model.Assessment
import com.schoolapp.core.model.GradeSection
import com.schoolapp.core.model.SchoolUser
import com.schoolapp.core.model.Subject
import com.schoolapp.core.model.TeacherAssignment
import com.schoolapp.core.model.UserRole

/**
 * Which classes a teacher may see and mark on this device: only the section + subject pairs
 * they are assigned to in their own school, the same scope the Firestore rules give them.
 * Admins manage every class of their school; students never change marks.
 */
object TeacherScope {
    const val NOT_ASSIGNED_MESSAGE = "You can only enter marks for the classes and subjects you are assigned to."

    /** [user]'s own assignments in their own school. */
    fun assignmentsOf(user: SchoolUser, assignments: List<TeacherAssignment>): List<TeacherAssignment> =
        assignments.filter { it.teacherUid == user.uid && it.schoolId == user.schoolId }

    /** Sections with at least one assignment in [own]. */
    fun visibleSections(sections: List<GradeSection>, own: List<TeacherAssignment>): List<GradeSection> {
        val sectionIds = own.mapTo(HashSet()) { it.sectionId }
        return sections.filter { it.sectionId in sectionIds }
    }

    /** Subjects of [sectionId] that [own] assigns. */
    fun visibleSubjects(sectionId: String, subjects: List<Subject>, own: List<TeacherAssignment>): List<Subject> {
        val subjectIds = own.filter { it.sectionId == sectionId }.mapTo(HashSet()) { it.subjectId }
        return subjects.filter { it.subjectId in subjectIds }
    }

    /** Assessments whose section + subject pair [own] assigns. */
    fun visibleAssessments(assessments: List<Assessment>, own: List<TeacherAssignment>): List<Assessment> =
        assessments.filter { isAssigned(it, own) }

    /**
     * Whether [user] may change a mark for [assessment] on this device, before anything is
     * written locally or queued for upload.
     *
     * [assessmentSchoolId] is the school of the assessment's section (null if that section is not
     * on this device) and [studentSectionId] the section of the student whose mark it is (null if
     * the student is not on this device).
     */
    fun mayEditScore(
        user: SchoolUser?,
        assessment: Assessment?,
        assessmentSchoolId: String?,
        studentSectionId: String?,
        assignments: List<TeacherAssignment>,
    ): Boolean {
        if (user == null || !user.active || assessment == null) return false
        if (assessmentSchoolId == null || assessmentSchoolId != user.schoolId) return false
        return when (user.role) {
            UserRole.ADMIN -> true
            UserRole.TEACHER ->
                studentSectionId == assessment.sectionId && isAssigned(assessment, assignmentsOf(user, assignments))
            UserRole.STUDENT -> false
        }
    }

    private fun isAssigned(assessment: Assessment, own: List<TeacherAssignment>): Boolean =
        own.any { it.sectionId == assessment.sectionId && it.subjectId == assessment.subjectId }
}
