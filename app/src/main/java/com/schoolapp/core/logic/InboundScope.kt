package com.schoolapp.core.logic

import com.schoolapp.core.model.Assessment
import com.schoolapp.core.model.GradeSection
import com.schoolapp.core.model.SchoolUser
import com.schoolapp.core.model.Student
import com.schoolapp.core.model.TeacherAssignment
import com.schoolapp.core.model.UserRole

/** Collections under schools/{schoolId} that are downloaded into Room, parents first. */
enum class SyncCollection(val path: String) {
    SECTIONS("sections"),
    SUBJECTS("subjects"),
    STUDENTS("students"),
    CYCLES("assessmentCycles"),
    ASSESSMENTS("assessments"),
    ASSIGNMENTS("teacherAssignments"),
    SCORES("scores"),
    SNAPSHOTS("resultSnapshots"),
}

/**
 * A row in Room as the download sees it: its id, the fields a [RemoteQuery] filters on, and
 * whether it holds a local change the server has not confirmed yet ([dirty]). [conflictKey] is
 * set for rows that also clash by content, not only by id (a score: assessment + student).
 */
data class LocalRow(
    val id: String,
    val fields: Map<String, Any?> = emptyMap(),
    val dirty: Boolean = false,
    val conflictKey: String? = null,
)

/**
 * One scoped download from schools/{schoolId}/{collection}. The filters are both what Firestore
 * runs and which local rows the answer speaks for ([matches]): a synced local row in scope that
 * the server no longer returns was deleted (or moved out of scope) on the server.
 */
data class RemoteQuery(
    val collection: SyncCollection,
    /** Field == value filters, all of which must hold. */
    val equalTo: List<Pair<String, Any>> = emptyList(),
    /** One optional `field in values` filter (at most [InboundScope.MAX_IN_VALUES] values). */
    val inField: String? = null,
    val inValues: List<Any> = emptyList(),
    /** Exact document ids instead of a scan (at most [InboundScope.MAX_IN_VALUES]). */
    val docIds: List<String>? = null,
    /** Also download changes by the server's updatedAt between full passes. */
    val incremental: Boolean = false,
) {
    init {
        require(inValues.size <= InboundScope.MAX_IN_VALUES) { "Firestore allows at most 30 'in' values" }
        require((docIds?.size ?: 0) <= InboundScope.MAX_IN_VALUES) { "Firestore allows at most 30 'in' values" }
        require(inField == null || inValues.isNotEmpty()) { "An 'in' filter needs values" }
    }

    /** Exact ids: a small, bounded answer fetched in one request. */
    val singleFetch: Boolean get() = docIds != null

    /** The whole collection, unfiltered (admin). */
    val wholeCollection: Boolean get() = equalTo.isEmpty() && inField == null && docIds == null

    /** Stable name for this query's download position (cursor). */
    val key: String
        get() = buildString {
            append(collection.path)
            equalTo.forEach { (field, value) -> append("|eq:").append(field).append('=').append(value) }
            if (inField != null) append("|in:").append(inField).append('=').append(inValues.joinToString(","))
            if (docIds != null) append("|ids=").append(docIds.joinToString(","))
            if (incremental) append("|inc")
        }

    /** Whether [row] is one this query answers for. */
    fun matches(row: LocalRow): Boolean {
        if (docIds != null && row.id !in docIds) return false
        if (equalTo.any { (field, value) -> !sameValue(row.fields[field], value) }) return false
        if (inField != null && inValues.none { sameValue(row.fields[inField], it) }) return false
        return true
    }

    private fun sameValue(local: Any?, wanted: Any): Boolean =
        if (local is Number && wanted is Number) local.toDouble() == wanted.toDouble() else local == wanted
}

/**
 * What the signed-in account downloads. Never more than the Firestore rules let it read, and
 * deliberately less where the app has no use for it:
 *
 * - ADMIN: the whole school. Every collection, unfiltered.
 * - TEACHER: their own assignments, and only the sections, subjects, students, assessments and
 *   marks those assignments cover, plus all cycles (needed to pick one). No result snapshots.
 *   Assessments are asked for one subject at a time and marks a few assessments at a time, the
 *   query shapes the teacher read rules can prove safe ([teacherAssessmentQueries],
 *   [teacherScoreQueries]).
 * - STUDENT: their own student record, their section, its grade's subjects and all cycles. No
 *   raw marks (results are shown only once published) and no assessments. Their published
 *   results are checked with the server by AnalyticsRepository.getStudentTrend (ResultSync),
 *   so they are not part of this download.
 *
 * Later phases depend on rows the earlier ones brought in (a teacher's sections come from their
 * assignments), so a run plans phase by phase from what is in Room by then ([ScopeFacts]).
 */
object InboundScope {
    /** Firestore's limit for an `in` filter. */
    const val MAX_IN_VALUES = 30

    /**
     * Assessment ids per teacher scores query. The rules check each id by reading its assessment
     * (one access call per id, on top of two for the caller's profile), and Firestore allows 10
     * access calls per query, so a chunk of [MAX_IN_VALUES] ids is refused as a whole.
     */
    const val MAX_SCORE_IDS_PER_QUERY = 8

    const val PHASES = 3

    /** What is in Room after the previous phase, as far as scoping needs it. */
    data class ScopeFacts(
        val assignments: List<TeacherAssignment> = emptyList(),
        val assessments: List<Assessment> = emptyList(),
        val ownStudent: Student? = null,
        val ownSection: GradeSection? = null,
    )

    fun queries(user: SchoolUser, phase: Int, facts: ScopeFacts): List<RemoteQuery> {
        if (!user.active) return emptyList()
        return when (user.role) {
            UserRole.ADMIN -> if (phase == 0) adminQueries() else emptyList()
            UserRole.TEACHER -> teacherQueries(user, phase, facts)
            UserRole.STUDENT -> studentQueries(user, phase, facts)
        }
    }

    /**
     * Collections whose local rows this role's download is responsible for. After a complete
     * run, synced rows in these collections that no query covers are removed. A student's
     * result snapshots are kept out: getStudentTrend reconciles those.
     */
    fun managedCollections(role: UserRole): List<SyncCollection> = when (role) {
        UserRole.ADMIN, UserRole.TEACHER -> SyncCollection.values().toList()
        UserRole.STUDENT -> SyncCollection.values().filter { it != SyncCollection.SNAPSHOTS }
    }

    /**
     * Ids of [localRows] that none of [queries] covers any more (a teacher unassigned from a
     * section, a student moved to another one) and that hold no unconfirmed local change.
     */
    fun outOfScope(localRows: List<LocalRow>, queries: List<RemoteQuery>): List<String> =
        localRows.filter { row -> !row.dirty && queries.none { it.matches(row) } }.map { it.id }

    private fun adminQueries(): List<RemoteQuery> =
        SyncCollection.values().map { collection ->
            // Only the backend writes result snapshots, and it always sets updatedAt.
            RemoteQuery(collection, incremental = collection == SyncCollection.SNAPSHOTS)
        }

    private fun teacherQueries(user: SchoolUser, phase: Int, facts: ScopeFacts): List<RemoteQuery> {
        val own = TeacherScope.assignmentsOf(user, facts.assignments)
        return when (phase) {
            0 -> listOf(RemoteQuery(SyncCollection.ASSIGNMENTS, equalTo = listOf("teacherUid" to user.uid)))
            1 -> {
                val sectionIds = own.map { it.sectionId }.distinct().sorted()
                val subjectIds = own.map { it.subjectId }.distinct().sorted()
                buildList {
                    addAll(byIds(SyncCollection.SECTIONS, sectionIds))
                    addAll(byIds(SyncCollection.SUBJECTS, subjectIds))
                    add(RemoteQuery(SyncCollection.CYCLES))
                    addAll(byFieldIn(SyncCollection.STUDENTS, "sectionId", sectionIds))
                    addAll(teacherAssessmentQueries(own))
                }
            }
            2 -> teacherScoreQueries(TeacherScope.visibleAssessments(facts.assessments, own).map { it.assessmentId })
            else -> emptyList()
        }
    }

    /** The sections [own] assigns each subject in: subjects in id order, each with its distinct sections in id order. */
    fun sectionsBySubject(own: List<TeacherAssignment>): Map<String, List<String>> =
        own.groupBy({ it.subjectId }, { it.sectionId }).toSortedMap()
            .mapValues { (_, sectionIds) -> sectionIds.distinct().sorted() }

    /**
     * A teacher's assessments: per assigned subject, `subjectId == S and sectionId in [S's
     * sections]`, at most [MAX_IN_VALUES] sections per query. The rules let a teacher read only
     * the assessments of an assigned section + subject pair, so a query by section alone (which
     * can match a subject someone else teaches there) is refused as a whole.
     */
    fun teacherAssessmentQueries(own: List<TeacherAssignment>): List<RemoteQuery> =
        sectionsBySubject(own).flatMap { (subjectId, sectionIds) ->
            sectionIds.chunked(MAX_IN_VALUES).map { chunk ->
                RemoteQuery(
                    SyncCollection.ASSESSMENTS,
                    equalTo = listOf("subjectId" to subjectId),
                    inField = "sectionId",
                    inValues = chunk,
                )
            }
        }

    /** A teacher's marks: `assessmentId in [ids]`, at most [MAX_SCORE_IDS_PER_QUERY] distinct ids per query, in id order. */
    fun teacherScoreQueries(assessmentIds: List<String>): List<RemoteQuery> =
        assessmentIds.distinct().sorted().chunked(MAX_SCORE_IDS_PER_QUERY).map { chunk ->
            RemoteQuery(SyncCollection.SCORES, inField = "assessmentId", inValues = chunk)
        }

    private fun studentQueries(user: SchoolUser, phase: Int, facts: ScopeFacts): List<RemoteQuery> = when (phase) {
        // The student's auth uid is their student id (createManagedUserAccount).
        0 -> listOf(
            RemoteQuery(SyncCollection.STUDENTS, docIds = listOf(user.uid)),
            RemoteQuery(SyncCollection.CYCLES),
        )
        1 -> facts.ownStudent?.takeIf { it.studentId == user.uid && it.sectionId.isNotBlank() }
            ?.let { listOf(RemoteQuery(SyncCollection.SECTIONS, docIds = listOf(it.sectionId))) }
            .orEmpty()
        2 -> facts.ownSection?.takeIf { it.sectionId == facts.ownStudent?.sectionId }
            ?.let { listOf(RemoteQuery(SyncCollection.SUBJECTS, equalTo = listOf("grade" to it.grade))) }
            .orEmpty()
        else -> emptyList()
    }

    private fun byIds(collection: SyncCollection, ids: List<String>): List<RemoteQuery> =
        ids.chunked(MAX_IN_VALUES).map { RemoteQuery(collection, docIds = it) }

    private fun byFieldIn(collection: SyncCollection, field: String, values: List<String>): List<RemoteQuery> =
        values.chunked(MAX_IN_VALUES).map { RemoteQuery(collection, inField = field, inValues = it) }
}
