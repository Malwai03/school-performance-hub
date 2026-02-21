package com.schoolapp.core.logic

import com.google.common.truth.Truth.assertThat
import com.schoolapp.core.model.Assessment
import com.schoolapp.core.model.PassStatus
import com.schoolapp.core.model.ScoreEntry
import com.schoolapp.core.model.ScoreStatus
import com.schoolapp.core.model.Subject
import org.junit.Test

class GradingEngineTest {

    @Test
    fun `returns INCOMPLETE when mandatory subject score missing`() {
        val subjects = listOf(
            Subject("math", "school", 6, "Math", 33.0, true),
        )
        val assessments = listOf(
            Assessment("a1", "c1", "s1", "math", "Test", 100.0, 1.0),
        )

        val result = GradingEngine.buildResultSnapshot(
            snapshotId = "snap1",
            cycleId = "c1",
            sectionId = "s1",
            studentId = "st1",
            subjects = subjects,
            assessments = assessments,
            scores = emptyList(),
            policy = GradingPolicy(),
        )

        assertThat(result.passStatus).isEqualTo(PassStatus.INCOMPLETE)
        assertThat(result.overallPercentage).isNull()
    }

    @Test
    fun `returns FAIL when one mandatory subject below threshold`() {
        val subjects = listOf(
            Subject("math", "school", 6, "Math", 33.0, true),
            Subject("science", "school", 6, "Science", 33.0, true),
        )
        val assessments = listOf(
            Assessment("m1", "c1", "s1", "math", "Math", 100.0, 1.0),
            Assessment("s1", "c1", "s1", "science", "Science", 100.0, 1.0),
        )
        val scores = listOf(
            ScoreEntry("x1", "m1", "st1", 70.0, ScoreStatus.PRESENT),
            ScoreEntry("x2", "s1", "st1", 25.0, ScoreStatus.PRESENT),
        )

        val result = GradingEngine.buildResultSnapshot(
            snapshotId = "snap2",
            cycleId = "c1",
            sectionId = "s1",
            studentId = "st1",
            subjects = subjects,
            assessments = assessments,
            scores = scores,
            policy = GradingPolicy(),
        )

        assertThat(result.passStatus).isEqualTo(PassStatus.FAIL)
        assertThat(result.overallPercentage).isEqualTo(47.5)
    }

    @Test
    fun `returns PASS when all mandatory subjects and overall meet thresholds`() {
        val subjects = listOf(
            Subject("math", "school", 6, "Math", 33.0, true),
            Subject("science", "school", 6, "Science", 33.0, true),
        )
        val assessments = listOf(
            Assessment("m1", "c1", "s1", "math", "Math", 100.0, 1.0),
            Assessment("s1", "c1", "s1", "science", "Science", 100.0, 1.0),
        )
        val scores = listOf(
            ScoreEntry("x1", "m1", "st1", 78.0, ScoreStatus.PRESENT),
            ScoreEntry("x2", "s1", "st1", 67.0, ScoreStatus.PRESENT),
        )

        val result = GradingEngine.buildResultSnapshot(
            snapshotId = "snap3",
            cycleId = "c1",
            sectionId = "s1",
            studentId = "st1",
            subjects = subjects,
            assessments = assessments,
            scores = scores,
            policy = GradingPolicy(defaultSubjectThresholdPct = 33.0, overallThresholdPct = 33.0),
        )

        assertThat(result.passStatus).isEqualTo(PassStatus.PASS)
        assertThat(result.overallPercentage).isEqualTo(72.5)
    }

    @Test
    fun `returns FAIL when overall below policy threshold despite subject pass`() {
        val subjects = listOf(
            Subject("math", "school", 6, "Math", 20.0, true),
            Subject("science", "school", 6, "Science", 20.0, true),
        )
        val assessments = listOf(
            Assessment("m1", "c1", "s1", "math", "Math", 100.0, 1.0),
            Assessment("s1", "c1", "s1", "science", "Science", 100.0, 1.0),
        )
        val scores = listOf(
            ScoreEntry("x1", "m1", "st1", 40.0, ScoreStatus.PRESENT),
            ScoreEntry("x2", "s1", "st1", 40.0, ScoreStatus.PRESENT),
        )

        val result = GradingEngine.buildResultSnapshot(
            snapshotId = "snap4",
            cycleId = "c1",
            sectionId = "s1",
            studentId = "st1",
            subjects = subjects,
            assessments = assessments,
            scores = scores,
            policy = GradingPolicy(defaultSubjectThresholdPct = 20.0, overallThresholdPct = 50.0),
        )

        assertThat(result.passStatus).isEqualTo(PassStatus.FAIL)
        assertThat(result.overallPercentage).isEqualTo(40.0)
    }
}
