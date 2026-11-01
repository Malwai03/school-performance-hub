package com.schoolapp.ui.common

import com.schoolapp.domain.repository.SyncOutcome
import com.schoolapp.domain.repository.SyncReport

/** What to tell the user after a download from the school server, or null if nothing. */
fun syncNoticeFor(report: SyncReport): String? = when (report.outcome) {
    SyncOutcome.FAILED -> "Couldn't reach the school server. Showing what's saved on this device."
    SyncOutcome.PARTIAL -> "Part of the school's data has downloaded. Pull down to refresh for the rest."
    SyncOutcome.DONE, SyncOutcome.SKIPPED, SyncOutcome.UNAVAILABLE, SyncOutcome.ACCOUNT_CHANGED -> null
}

/** Whether the screen should reload what is in the local database. */
val SyncReport.changedLocalData: Boolean
    get() = downloaded > 0 || removed > 0 || outcome == SyncOutcome.DONE || outcome == SyncOutcome.PARTIAL
