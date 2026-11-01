package com.schoolapp.core.logic

import com.schoolapp.core.model.ResultSnapshot

/**
 * Brings a student's cached result snapshots in line with what the server returned for them.
 *
 * The server's answer is authoritative: new and corrected results are stored, and results the
 * server no longer has (withdrawn) are removed. A document that came back but could not be read
 * is left as cached rather than deleted. When the server could not be asked at all (offline,
 * refused) nothing changes and the cached results are flagged as possibly out of date.
 */
object ResultSync {
    /** What the server returned: every document id, and the documents readable as results. */
    data class ServerResults(
        val docIds: Collection<String>,
        val readable: List<ResultSnapshot>,
    )

    data class Plan(
        /** Results to store as the server has them. */
        val upserts: List<ResultSnapshot>,
        /** Cached results the server no longer has. */
        val deleteIds: List<String>,
        /** The server could not be asked: the cache is kept as is and may be out of date. */
        val serverUnreachable: Boolean,
    )

    /** [server] is null when the server could not be asked. */
    fun reconcile(studentId: String, localIds: Collection<String>, server: ServerResults?): Plan {
        if (server == null) return Plan(emptyList(), emptyList(), serverUnreachable = true)
        val returned = server.docIds.toHashSet()
        return Plan(
            upserts = server.readable.filter { it.studentId == studentId && it.snapshotId in returned },
            deleteIds = localIds.filter { it !in returned },
            serverUnreachable = false,
        )
    }
}
