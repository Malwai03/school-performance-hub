package com.schoolapp.core.logic

import com.google.common.truth.Truth.assertThat
import com.schoolapp.core.model.PassStatus
import com.schoolapp.core.model.ResultSnapshot
import com.schoolapp.core.model.RiskLevel
import org.junit.Test

class ResultSyncTest {

    private val student = "student-1"

    private fun result(cycleId: String, overall: Double, studentId: String = student) = ResultSnapshot(
        snapshotId = "${cycleId}_g6_a_$studentId",
        cycleId = cycleId,
        sectionId = "g6_a",
        studentId = studentId,
        subjectPercentages = mapOf("sub_math_6" to overall),
        overallPercentage = overall,
        passStatus = PassStatus.PASS,
        riskLevel = RiskLevel.LOW,
    )

    private fun server(vararg results: ResultSnapshot, unreadableIds: List<String> = emptyList()) =
        ResultSync.ServerResults(docIds = results.map { it.snapshotId } + unreadableIds, readable = results.toList())

    /** The device cache (Room) after applying [plan], keyed by snapshot id. */
    private fun apply(cache: List<ResultSnapshot>, plan: ResultSync.Plan): Map<String, ResultSnapshot> {
        val rows = cache.associateBy { it.snapshotId }.toMutableMap()
        plan.upserts.forEach { rows[it.snapshotId] = it }
        plan.deleteIds.forEach { rows.remove(it) }
        return rows
    }

    private fun refresh(cache: List<ResultSnapshot>, server: ResultSync.ServerResults?): Pair<Map<String, ResultSnapshot>, Boolean> {
        val plan = ResultSync.reconcile(student, cache.map { it.snapshotId }, server)
        return apply(cache, plan) to plan.serverUnreachable
    }

    @Test
    fun `a refresh brings in a corrected March and a newly published April`() {
        val march = result("cycle_2026_03", 60.0)
        val correctedMarch = result("cycle_2026_03", 72.0)
        val april = result("cycle_2026_04", 81.0)

        // The cache already holds March, which is where the old code stopped asking the server.
        var cache = listOf(march)
        repeat(2) {
            val (rows, stale) = refresh(cache, server(correctedMarch, april))
            assertThat(stale).isFalse()
            assertThat(rows.values).containsExactly(correctedMarch, april)
            cache = rows.values.toList()
        }
    }

    @Test
    fun `a result withdrawn on the server disappears from the cache`() {
        val march = result("cycle_2026_03", 60.0)
        val april = result("cycle_2026_04", 81.0)

        val (rows, stale) = refresh(listOf(march, april), server(april))

        assertThat(stale).isFalse()
        assertThat(rows.values).containsExactly(april)
    }

    @Test
    fun `the server having no results clears the cache`() {
        val (rows, _) = refresh(listOf(result("cycle_2026_03", 60.0)), server())
        assertThat(rows).isEmpty()
    }

    @Test
    fun `offline the cache is kept and flagged as possibly out of date`() {
        val march = result("cycle_2026_03", 60.0)

        val (rows, stale) = refresh(listOf(march), server = null)

        assertThat(stale).isTrue()
        assertThat(rows.values).containsExactly(march)
    }

    @Test
    fun `a returned document that cannot be read keeps its cached copy`() {
        val march = result("cycle_2026_03", 60.0)
        val april = result("cycle_2026_04", 81.0)

        val (rows, _) = refresh(listOf(march), server(april, unreadableIds = listOf(march.snapshotId)))

        assertThat(rows.values).containsExactly(march, april)
    }

    @Test
    fun `another student's results are never stored`() {
        val mine = result("cycle_2026_03", 60.0)
        val theirs = result("cycle_2026_03", 95.0, studentId = "student-2")

        val plan = ResultSync.reconcile(student, emptyList(), server(mine, theirs))

        assertThat(plan.upserts).containsExactly(mine)
    }
}
