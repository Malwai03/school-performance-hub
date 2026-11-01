package com.schoolapp.core.logic

/**
 * How a downloaded page is merged into Room. The rules:
 *
 * 1. A local change the server has not confirmed (an unsynced mark, a master-data change still
 *    uploading or uploaded after this page was fetched) is never overwritten or deleted by a
 *    download. It is kept, and the ordered upload pipeline sends it.
 * 2. A document this device itself still has a queued write for (Firestore's pending write) is
 *    skipped: it already shows the local change.
 * 3. Otherwise the server wins: its version of a synced row replaces the local one.
 * 4. A synced local row in the query's scope that the server no longer returns was deleted on
 *    the server (or moved out of scope) and is removed. This is only concluded for the id range
 *    the answer is known to be complete for ([Coverage]).
 * 5. A document that came back but could not be read is ignored: the local copy is left alone,
 *    neither replaced nor deleted.
 */
object InboundMerge {
    /** A downloaded document: [row] is null when it could not be read. */
    data class ServerDoc<T>(
        val id: String,
        val row: T?,
        val pendingWrite: Boolean = false,
    )

    /** What part of the query's scope an answer is complete for. */
    sealed interface Coverage {
        /** Everything in scope (a single fetch of exact ids). */
        data object Everything : Coverage

        /** Every document with [afterId] < id <= [throughId] in Firestore's id order; null = open end. */
        data class IdRange(val afterId: String?, val throughId: String?) : Coverage

        /** Only changed documents (an updatedAt download): nothing follows from a missing one. */
        data object ChangesOnly : Coverage
    }

    data class Plan<T>(
        /** Server versions to store, marked synced. */
        val upserts: List<T>,
        /** Synced local rows the server no longer has. */
        val deleteIds: List<String>,
        /** Ids where the local change was kept over the server's version (or its absence). */
        val keptLocalIds: List<String>,
    )

    /**
     * Plans one page.
     *
     * @param candidates local rows that the page could delete (those in the page's id range, or in
     *   the exact ids); filtered again by [inScope] and [coverage].
     * @param dirtyIds ids anywhere in the collection that hold an unconfirmed local change.
     * @param dirtyConflictKeys [LocalRow.conflictKey]s of rows with an unconfirmed local change.
     */
    fun <T> plan(
        server: List<ServerDoc<T>>,
        candidates: List<LocalRow>,
        dirtyIds: Set<String>,
        dirtyConflictKeys: Set<String> = emptySet(),
        inScope: (LocalRow) -> Boolean,
        coverage: Coverage,
        conflictKeyOf: (T) -> String? = { null },
    ): Plan<T> {
        val upserts = mutableListOf<T>()
        val kept = LinkedHashSet<String>()
        for (doc in server) {
            val row = doc.row ?: continue
            val clashes = conflictKeyOf(row)?.let { it in dirtyConflictKeys } == true
            when {
                doc.pendingWrite || doc.id in dirtyIds || clashes -> kept += doc.id
                else -> upserts += row
            }
        }

        val returned = server.mapTo(HashSet()) { it.id }
        val gone = candidates.filter { row ->
            row.id !in returned && inScope(row) && covers(coverage, row.id)
        }
        val deleteIds = mutableListOf<String>()
        gone.forEach { row ->
            if (row.dirty || row.id in dirtyIds) kept += row.id else deleteIds += row.id
        }
        return Plan(upserts, deleteIds, kept.toList())
    }

    fun covers(coverage: Coverage, id: String): Boolean = when (coverage) {
        Coverage.Everything -> true
        Coverage.ChangesOnly -> false
        is Coverage.IdRange ->
            (coverage.afterId == null || FirestoreIdOrder.compare(id, coverage.afterId) > 0) &&
                (coverage.throughId == null || FirestoreIdOrder.compare(id, coverage.throughId) <= 0)
    }
}

/**
 * Firestore orders document ids by their UTF-8 bytes. SQLite's default (BINARY) collation
 * compares the same bytes, so a Room range query and this comparator agree with Firestore.
 */
object FirestoreIdOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val diff = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return x.size - y.size
    }
}

/** A position in an updatedAt-ordered download: the last document's server time and id. */
data class ChangesCursor(val seconds: Long, val nanos: Int, val docId: String)

/** Where one [RemoteQuery]'s download stands between runs. */
data class ScanState(
    /** A full, id-ordered pass is in progress and continues after this id. */
    val idCursor: String? = null,
    /** When the last full pass finished (device clock, ms). */
    val lastFullAtMillis: Long? = null,
    /** Position of the updatedAt download (incremental queries only). */
    val changesCursor: ChangesCursor? = null,
)

enum class ScanMode {
    /** Everything in scope, ordered by id, with deletions. */
    FULL,

    /** Only documents whose updatedAt is after the cursor; finds no deletions. */
    CHANGES,
}

/** Page sizes and per-run bounds, so no single run (least of all the first) reads without end. */
data class SyncLimits(
    val pageSize: Int = 500,
    /** One query stops after this many documents in a run and resumes from its cursor next run. */
    val maxDocsPerQueryPerRun: Int = 5_000,
    /** A whole run stops after this many documents. */
    val maxDocsPerRun: Int = 20_000,
)

/** When downloads run and in which mode. */
object InboundSchedule {
    /** A full pass (which also finds server deletions) at least this often for incremental queries. */
    const val FULL_PASS_EVERY_MS: Long = 24L * 60 * 60 * 1000

    /** Automatic runs (opening a screen) are skipped if a run finished this recently. */
    const val AUTOMATIC_MIN_INTERVAL_MS: Long = 60L * 60 * 1000

    fun mode(query: RemoteQuery, state: ScanState, nowMillis: Long): ScanMode = when {
        !query.incremental -> ScanMode.FULL
        state.idCursor != null -> ScanMode.FULL // finish the full pass that is under way
        state.lastFullAtMillis != null && isDue(state.lastFullAtMillis, nowMillis, FULL_PASS_EVERY_MS) -> ScanMode.FULL
        // No cursor yet: the first download runs in updatedAt order from the start.
        else -> ScanMode.CHANGES
    }

    /** After a full-pass page; [lastIdOnPage] null means the page was empty, so the pass is done. */
    fun afterFullPage(state: ScanState, lastIdOnPage: String?, nowMillis: Long): ScanState =
        if (lastIdOnPage == null) {
            state.copy(idCursor = null, lastFullAtMillis = nowMillis)
        } else {
            state.copy(idCursor = lastIdOnPage)
        }

    /**
     * After an updatedAt page; [last] null means nothing newer is left. The first download that
     * reaches the end counts as a full pass, so the next one (which finds deletions) is a day later.
     */
    fun afterChangesPage(state: ScanState, last: ChangesCursor?, nowMillis: Long): ScanState = when {
        last != null -> state.copy(changesCursor = last)
        state.lastFullAtMillis == null -> state.copy(lastFullAtMillis = nowMillis)
        else -> state
    }

    /** Opening a screen syncs only if no run finished in the last hour. Pull-to-refresh always runs. */
    fun shouldRunAutomatically(lastRunAtMillis: Long?, nowMillis: Long): Boolean =
        lastRunAtMillis == null || isDue(lastRunAtMillis, nowMillis, AUTOMATIC_MIN_INTERVAL_MS)

    /** A clock that went backwards counts as due rather than never. */
    private fun isDue(sinceMillis: Long, nowMillis: Long, everyMillis: Long): Boolean {
        val elapsed = nowMillis - sinceMillis
        return elapsed < 0 || elapsed >= everyMillis
    }
}

/** [ScanState] as one stored string. */
object ScanStateCodec {
    fun encode(state: ScanState): String = listOf(
        state.idCursor,
        state.lastFullAtMillis?.toString(),
        state.changesCursor?.seconds?.toString(),
        state.changesCursor?.nanos?.toString(),
        state.changesCursor?.docId,
    ).joinToString("|") { it?.let(::escape) ?: "" }

    /** Anything unreadable decodes as a fresh state, which only means downloading again. */
    fun decode(encoded: String?): ScanState {
        if (encoded.isNullOrEmpty()) return ScanState()
        val parts = encoded.split('|').map { unescape(it).ifEmpty { null } }
        if (parts.size != 5) return ScanState()
        val seconds = parts[2]?.toLongOrNull()
        val nanos = parts[3]?.toIntOrNull()
        val docId = parts[4]
        return ScanState(
            idCursor = parts[0],
            lastFullAtMillis = parts[1]?.toLongOrNull(),
            changesCursor = if (seconds != null && nanos != null && docId != null) ChangesCursor(seconds, nanos, docId) else null,
        )
    }

    private fun escape(value: String): String = value.replace("%", "%25").replace("|", "%7C")

    private fun unescape(value: String): String = value.replace("%7C", "|").replace("%25", "%")
}
