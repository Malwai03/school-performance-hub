package com.schoolapp.core.logic

import com.google.common.truth.Truth.assertThat
import com.schoolapp.core.model.Assessment
import com.schoolapp.core.model.GradeSection
import com.schoolapp.core.model.SchoolUser
import com.schoolapp.core.model.Subject
import com.schoolapp.core.model.TeacherAssignment
import com.schoolapp.core.model.UserRole
import org.junit.Test

class TeacherScopeTest {

    private val school = "school_001"
    private val teacher = SchoolUser("t1", school, UserRole.TEACHER, "Maya", "teacher001", true)
    private val otherTeacher = SchoolUser("t2", school, UserRole.TEACHER, "Ravi", "teacher002", true)
    private val admin = SchoolUser("a1", school, UserRole.ADMIN, "Principal", "admin001", true)
    private val student = SchoolUser("s1", school, UserRole.STUDENT, "Rohan", "student001", true)

    private val sectionA = GradeSection("g6_a", school, 6, "A")
    private val sectionB = GradeSection("g6_b", school, 6, "B")
    private val math = Subject("sub_math_6", school, 6, "Mathematics")
    private val science = Subject("sub_science_6", school, 6, "Science")
    private val english = Subject("sub_english_6", school, 6, "English")

    private val mathA = Assessment("assess_math_a", "cycle1", sectionA.sectionId, math.subjectId, "Math UT", 100.0)
    private val scienceA = Assessment("assess_science_a", "cycle1", sectionA.sectionId, science.subjectId, "Science UT", 100.0)
    private val mathB = Assessment("assess_math_b", "cycle1", sectionB.sectionId, math.subjectId, "Math UT", 100.0)

    /** Room holds every class of the school: the teacher is assigned A/math only, t2 has B/math. */
    private val assignments = listOf(
        TeacherAssignment("g6_a_math_t1", school, sectionA.sectionId, math.subjectId, teacher.uid),
        TeacherAssignment("g6_b_math_t2", school, sectionB.sectionId, math.subjectId, otherTeacher.uid),
    )

    private fun mayEdit(
        user: SchoolUser?,
        assessment: Assessment?,
        studentSectionId: String? = assessment?.sectionId,
        assessmentSchoolId: String? = school,
    ) = TeacherScope.mayEditScore(user, assessment, assessmentSchoolId, studentSectionId, assignments)

    @Test
    fun `a teacher sees only the sections and subjects they are assigned to`() {
        val own = TeacherScope.assignmentsOf(teacher, assignments)

        assertThat(TeacherScope.visibleSections(listOf(sectionA, sectionB), own)).containsExactly(sectionA)
        assertThat(TeacherScope.visibleSubjects(sectionA.sectionId, listOf(math, science, english), own))
            .containsExactly(math)
        assertThat(TeacherScope.visibleSubjects(sectionB.sectionId, listOf(math, science, english), own)).isEmpty()
        assertThat(TeacherScope.visibleAssessments(listOf(mathA, scienceA, mathB), own)).containsExactly(mathA)
    }

    @Test
    fun `a teacher with no assignments sees no classes`() {
        val own = TeacherScope.assignmentsOf(teacher, emptyList())

        assertThat(TeacherScope.visibleSections(listOf(sectionA, sectionB), own)).isEmpty()
        assertThat(TeacherScope.visibleAssessments(listOf(mathA, scienceA, mathB), own)).isEmpty()
    }

    @Test
    fun `another teacher's or another school's assignments do not count`() {
        val foreign = listOf(
            TeacherAssignment("x", "school_002", sectionA.sectionId, science.subjectId, teacher.uid),
            TeacherAssignment("y", school, sectionA.sectionId, english.subjectId, otherTeacher.uid),
        )
        assertThat(TeacherScope.assignmentsOf(teacher, foreign)).isEmpty()
    }

    @Test
    fun `a teacher may change marks only for an assigned section and subject`() {
        assertThat(mayEdit(teacher, mathA)).isTrue()
        assertThat(mayEdit(teacher, scienceA)).isFalse()
        assertThat(mayEdit(teacher, mathB)).isFalse()
    }

    @Test
    fun `a teacher may not mark a student from another section on an assigned assessment`() {
        assertThat(mayEdit(teacher, mathA, studentSectionId = sectionB.sectionId)).isFalse()
        assertThat(mayEdit(teacher, mathA, studentSectionId = null)).isFalse()
    }

    @Test
    fun `admins may change any mark of their own school`() {
        assertThat(mayEdit(admin, mathA)).isTrue()
        assertThat(mayEdit(admin, scienceA)).isTrue()
        assertThat(mayEdit(admin, mathB)).isTrue()
    }

    @Test
    fun `nobody changes marks of another school, an unknown assessment, or while signed out`() {
        assertThat(mayEdit(admin, mathA, assessmentSchoolId = "school_002")).isFalse()
        assertThat(mayEdit(teacher, mathA, assessmentSchoolId = null)).isFalse()
        assertThat(mayEdit(teacher, null)).isFalse()
        assertThat(mayEdit(null, mathA)).isFalse()
    }

    @Test
    fun `students and inactive accounts never change marks`() {
        assertThat(mayEdit(student, mathA)).isFalse()
        assertThat(mayEdit(teacher.copy(active = false), mathA)).isFalse()
        assertThat(mayEdit(admin.copy(active = false), mathA)).isFalse()
    }
}
