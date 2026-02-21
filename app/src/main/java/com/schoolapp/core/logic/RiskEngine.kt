package com.schoolapp.core.logic

import com.schoolapp.core.model.PassStatus
import com.schoolapp.core.model.RiskLevel

object RiskEngine {

    fun deriveRisk(
        passStatus: PassStatus,
        overallPct: Double?,
        overallThresholdPct: Double,
        trend: List<TrendPoint>,
    ): RiskLevel {
        if (passStatus == PassStatus.FAIL || overallPct == null) {
            return RiskLevel.HIGH
        }

        val gap = overallPct - overallThresholdPct
        val drop = calculateDrop(trend, overallPct)

        return when {
            gap < 3.0 || drop >= 12.0 -> RiskLevel.HIGH
            gap < 8.0 || drop >= 6.0 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }

    private fun calculateDrop(trend: List<TrendPoint>, current: Double): Double {
        val previous = trend.lastOrNull()?.overallPct ?: return 0.0
        return (previous - current).coerceAtLeast(0.0)
    }
}
