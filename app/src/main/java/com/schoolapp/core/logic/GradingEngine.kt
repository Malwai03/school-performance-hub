package com.schoolapp.core.logic

import com.schoolapp.core.model.Assessment
import com.schoolapp.core.model.PassStatus
import com.schoolapp.core.model.ResultSnapshot
import com.schoolapp.core.model.RiskLevel
import com.schoolapp.core.model.ScoreEntry
import com.schoolapp.core.model.ScoreStatus
import com.schoolapp.core.model.Subject
import kotlin.math.max

data class GradingPolicy(
    val defaultSubjectThresholdPct: Double = 33.0,
    val overallThresholdPct: Double = 33.0,
)

data class TrendPoint(
    val cycleId: String,
    val overallPct: Double,
)

object GradingEngine {

    fun buildResultSnapshot(
        snapshotId: String,
        cycleId: String,
        sectionId: String,
        studentId: String,
        subjects: List<Subject>,
        assessments: List<Assessment>,
        scores: List<ScoreEntry>,
        policy: GradingPolicy,
        previousTrend: List<TrendPoint> = emptyList(),
    ): ResultSnapshot {
        val subjectById = subjects.associateBy { it.subjectId }
        val assessmentsBySubject = assessments.groupBy { it.subjectId }
        val scoresByAssessment = scores.associateBy { it.assessmentId }

        val subjectPercentages = linkedMapOf<String, Double>()
        var hasIncomplete = false
        var hasFailedSubject = false

        subjectById.values.filter { it.mandatory }.forEach { subject ->
            val assessmentsForSubject = assessmentsBySubject[subject.subjectId].orEmpty()
            val subjectResult = computeSubjectPercentage(assessmentsForSubject, scoresByAssessment)
            if (!subjectResult.complete) {
                hasIncomplete = true
            } else {
                subjectPercentages[subject.subjectId] = subjectResult.percentage
                val threshold = subject.passThresholdPct.takeIf { it > 0.0 } ?: policy.defaultSubjectThresholdPct
                if (subjectResult.percentage < threshold) {
                    hasFailedSubject = true
                }
            }
        }

        val overall = if (subjectPercentages.isEmpty()) {
            null
        } else {
            subjectPercentages.values.average().round2()
        }

        val passStatus = when {
            hasIncomplete || overall == null -> PassStatus.INCOMPLETE
            hasFailedSubject -> PassStatus.FAIL
            overall < policy.overallThresholdPct -> PassStatus.FAIL
            else -> PassStatus.PASS
        }

        val risk = RiskEngine.deriveRisk(
            passStatus = passStatus,
            overallPct = overall,
            overallThresholdPct = policy.overallThresholdPct,
            trend = previousTrend,
        )

        return ResultSnapshot(
            snapshotId = snapshotId,
            cycleId = cycleId,
            sectionId = sectionId,
            studentId = studentId,
            subjectPercentages = subjectPercentages,
            overallPercentage = overall,
            passStatus = passStatus,
            riskLevel = risk,
        )
    }

    private data class SubjectComputation(
        val percentage: Double,
        val complete: Boolean,
    )

    private fun computeSubjectPercentage(
        assessmentsForSubject: List<Assessment>,
        scoresByAssessment: Map<String, ScoreEntry>,
    ): SubjectComputation {
        if (assessmentsForSubject.isEmpty()) {
            return SubjectComputation(0.0, complete = false)
        }

        var weightedPctTotal = 0.0
        var weightTotal = 0.0

        for (assessment in assessmentsForSubject) {
            val score = scoresByAssessment[assessment.assessmentId] ?: return SubjectComputation(0.0, false)
            val normalizedMarks = when (score.status) {
                ScoreStatus.PRESENT -> score.obtainedMarks.coerceIn(0.0, assessment.maxMarks)
                ScoreStatus.ABSENT -> 0.0
                ScoreStatus.EXEMPT -> null
            }

            if (normalizedMarks == null) continue
            if (assessment.maxMarks <= 0.0) continue

            val pct = (normalizedMarks / assessment.maxMarks) * 100.0
            val weight = max(assessment.weight, 0.0)
            weightedPctTotal += pct * weight
            weightTotal += weight
        }

        if (weightTotal <= 0.0) {
            return SubjectComputation(0.0, false)
        }

        return SubjectComputation((weightedPctTotal / weightTotal).round2(), true)
    }
}

private fun Double.round2(): Double = kotlin.math.round(this * 100) / 100
