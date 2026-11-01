package com.schoolapp.data.sync

import com.schoolapp.core.logic.ChangesCursor
import com.schoolapp.core.logic.InboundMerge
import com.schoolapp.core.logic.InboundSchedule
import com.schoolapp.core.logic.InboundScope
import com.schoolapp.core.logic.LocalRow
import com.schoolapp.core.logic.RemoteQuery
import com.schoolapp.core.logic.ScanMode
import com.schoolapp.core.logic.ScanState
import com.schoolapp.core.logic.SyncCollection
import com.schoolapp.core.logic.SyncLimits
import com.schoolapp.core.model.SchoolUser
import com.schoolapp.data.local.entity.ScoreEntity
import com.schoolapp.domain.repository.SyncOutcome
import com.schoolapp.domain.repository.SyncReport
import com.schoolapp.domain.repository.SyncTrigger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A downloaded document. [updatedAt] is the server's updatedAt, if it has one. */
internal data class RemoteDoc(
    val id: String,
    val data: Map<String, Any?>,
    val pendingWrite: Boolean = false,
    val updatedAt: ServerTime? = null,
)

internal data class ServerTime(val seconds: Long, val nanos: Int)

internal data class FetchRequest(
    val schoolId: String,
    val query: RemoteQuery,
    val mode: ScanMode,
    val afterId: String?,
    val afterChange: ChangesCursor?,
    val limit: Int,
)

/** The server: one page of a query, from the server itself (never the offline cache). */
internal fun interface InboundRemote {
    suspend fun fetch(request: FetchRequest): List<RemoteDoc>
}

/** Which local rows a page is complete for, so the merge can find server deletions among them. */
internal sealed interface RowSelection {
    data object None : RowSelection
    data object All : RowSelection
    data class Ids(val ids: List<String>) : RowSelection
    data class IdRange(val afterId: String?, val throughId: String?) : RowSelection
}

/** Room. */
internal interface InboundLocal {
    suspend fun facts(user: SchoolUser): InboundScope.ScopeFacts

    /**
     * Under the local-data lock and in one transaction: if [stillValid] (the same account still
     * owns the local data and is signed in), reads the [selection]'s rows and the collection's
     * rows with unsynced local changes, lets [plan] decide, and writes the plan. Returns false,
     * writing nothing, when the account changed.
     */
    suspend fun withPage(
        collection: SyncCollection,
        selection: RowSelection,
        stillValid: suspend () -> Boolean,
        plan: (candidates: List<LocalRow>, dirty: List<LocalRow>) -> InboundMerge.Plan<Any>,
    ): Boolean
}

/** Download positions, per account and school. */
internal data class InboundSyncState(
    val scans: Map<String, ScanState> = emptyMap(),
    val lastRunAtMillis: Long? = null,
)

internal interface InboundStateStore {
    /** Another account's (or school's) stored state reads as empty. */
    suspend fun read(owner: String): InboundSyncState
    suspend fun write(owner: String, state: InboundSyncState)
}

/**
 * Downloads the signed-in account's share of its school into Room ([InboundScope]), merging
 * each page by [InboundMerge]. Only the Firestore and Room plumbing sits behind the interfaces,
 * so the whole run (phases, budget, cursors, ownership) is tested with fakes.
 *
 * - One run at a time.
 * - Every page is applied in its own transaction under the local-data lock, and only while the
 *   same account still owns the local data: a sign-out or account switch mid-run stops it, and
 *   nothing is ever written into another account's data.
 * - Bounded: [SyncLimits]. A query that hits its bound keeps its cursor and resumes next run.
 * - A run that could not finish (offline, refused) keeps what it already applied.
 */
internal class InboundSyncEngine(
    private val remote: InboundRemote,
    private val local: InboundLocal,
    private val stateStore: InboundStateStore,
    private val tracker: LocalChangeTracker,
    private val limits: SyncLimits = SyncLimits(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val runLock = Mutex()

    private class AccountChanged : Exception()

    /**
     * [stillValid] says whether [user] still owns the local data and is signed in; it is checked
     * before every write. Network and permission errors propagate after the pages already
     * applied are kept.
     */
    suspend fun run(
        user: SchoolUser,
        schoolId: String,
        trigger: SyncTrigger,
        stillValid: suspend () -> Boolean,
    ): SyncReport = runLock.withLock {
        val owner = "${user.uid}@$schoolId"
        val run = Run(user, schoolId, owner, stillValid, stateStore.read(owner))
        if (trigger == SyncTrigger.APP_OPEN && !InboundSchedule.shouldRunAutomatically(run.state.lastRunAtMillis, now())) {
            return@withLock SyncReport(SyncOutcome.SKIPPED)
        }
        try {
            run.execute()
        } catch (e: AccountChanged) {
            run.report(SyncOutcome.ACCOUNT_CHANGED)
        } finally {
            tracker.forgetTouches()
        }
    }

    private inner class Run(
        val user: SchoolUser,
        val schoolId: String,
        val owner: String,
        val stillValid: suspend () -> Boolean,
        var state: InboundSyncState,
    ) {
        private var budget = limits.maxDocsPerRun
        private var downloaded = 0
        private var removed = 0
        private var kept = 0
        private val planned = mutableListOf<RemoteQuery>()
        private val runToken = tracker.token()

        fun report(outcome: SyncOutcome) = SyncReport(outcome, downloaded = downloaded, removed = removed, keptLocal = kept)

        suspend fun execute(): SyncReport {
            var complete = true
            for (phase in 0 until InboundScope.PHASES) {
                val queries = InboundScope.queries(user, phase, local.facts(user))
                    .filter { query -> planned.none { it.key == query.key } }
                planned += queries
                for (query in queries) {
                    if (!download(query)) complete = false
                }
            }
            if (complete) prune()
            state = state.copy(
                lastRunAtMillis = now(),
                // A complete run knows every query it needs: positions of older ones are dropped.
                scans = if (complete) state.scans.filterKeys { key -> planned.any { it.key == key } } else state.scans,
            )
            stateStore.write(owner, state)
            return report(if (complete) SyncOutcome.DONE else SyncOutcome.PARTIAL)
        }

        /** Downloads [query] as far as the bounds allow. False if it stopped at a bound. */
        private suspend fun download(query: RemoteQuery): Boolean {
            if (query.singleFetch) return fetchOnce(query)
            var scan = state.scans[query.key] ?: ScanState()
            var readByQuery = 0
            while (true) {
                val allowance = minOf(limits.pageSize, limits.maxDocsPerQueryPerRun - readByQuery, budget)
                if (allowance <= 0) return false
                val mode = InboundSchedule.mode(query, scan, now())
                val token = tracker.token()
                val docs = remote.fetch(FetchRequest(schoolId, query, mode, scan.idCursor, scan.changesCursor, allowance))
                // An empty answer is still billed as one read.
                val cost = maxOf(1, docs.size)
                budget -= cost
                readByQuery += cost
                when (mode) {
                    ScanMode.FULL -> {
                        val lastId = docs.lastOrNull()?.id
                        apply(
                            query, docs, token,
                            InboundMerge.Coverage.IdRange(scan.idCursor, lastId),
                            RowSelection.IdRange(scan.idCursor, lastId),
                        )
                        scan = InboundSchedule.afterFullPage(scan, lastId, now())
                        save(query, scan)
                        if (lastId == null) return true
                    }
                    ScanMode.CHANGES -> {
                        apply(query, docs, token, InboundMerge.Coverage.ChangesOnly, RowSelection.None)
                        val lastDoc = docs.lastOrNull()
                        val last = lastDoc?.updatedAt?.let { ChangesCursor(it.seconds, it.nanos, lastDoc.id) }
                        scan = InboundSchedule.afterChangesPage(scan, last, now())
                        save(query, scan)
                        // Done when nothing newer is left (or the cursor cannot move on).
                        if (last == null) return true
                    }
                }
            }
        }

        /** Exact ids: one request, complete for its ids unless it somehow filled the page. */
        private suspend fun fetchOnce(query: RemoteQuery): Boolean {
            if (budget <= 0) return false
            val token = tracker.token()
            val docs = remote.fetch(FetchRequest(schoolId, query, ScanMode.FULL, null, null, limits.pageSize))
            budget -= maxOf(1, docs.size)
            val coverage = if (docs.size < limits.pageSize) InboundMerge.Coverage.Everything else InboundMerge.Coverage.ChangesOnly
            apply(query, docs, token, coverage, RowSelection.Ids(query.docIds.orEmpty()))
            save(query, (state.scans[query.key] ?: ScanState()).copy(lastFullAtMillis = now()))
            return true
        }

        private suspend fun apply(
            query: RemoteQuery,
            docs: List<RemoteDoc>,
            fetchToken: Long,
            coverage: InboundMerge.Coverage,
            selection: RowSelection,
        ) {
            val server = docs.map { doc ->
                InboundMerge.ServerDoc(doc.id, InboundDocs.parse(query.collection, schoolId, doc.id, doc.data), doc.pendingWrite)
            }
            val applied = local.withPage(query.collection, selection, stillValid) { candidates, dirty ->
                // Changed on this device since the page was fetched, or still uploading: keep it.
                val dirtyIds = dirty.mapTo(HashSet()) { it.id } + tracker.busySince(fetchToken, query.collection)
                InboundMerge.plan(
                    server = server,
                    candidates = candidates,
                    dirtyIds = dirtyIds,
                    dirtyConflictKeys = dirty.mapNotNullTo(HashSet()) { it.conflictKey },
                    inScope = query::matches,
                    coverage = coverage,
                    conflictKeyOf = { row -> (row as? ScoreEntity)?.let { InboundDocs.scoreConflictKey(it.assessmentId, it.studentId) } },
                ).also { count(it) }
            }
            if (!applied) throw AccountChanged()
        }

        /** Removes synced rows no query of this role covers any more (only after a complete run). */
        private suspend fun prune() {
            for (collection in InboundScope.managedCollections(user.role)) {
                val queries = planned.filter { it.collection == collection }
                if (queries.any { it.wholeCollection }) continue
                val applied = local.withPage(collection, RowSelection.All, stillValid) { candidates, dirty ->
                    val dirtyIds = dirty.mapTo(HashSet()) { it.id } + tracker.busySince(runToken, collection)
                    val rows = candidates.map { if (it.id in dirtyIds) it.copy(dirty = true) else it }
                    InboundMerge.Plan<Any>(emptyList(), InboundScope.outOfScope(rows, queries), emptyList()).also { count(it) }
                }
                if (!applied) throw AccountChanged()
            }
        }

        private fun count(plan: InboundMerge.Plan<*>) {
            downloaded += plan.upserts.size
            removed += plan.deleteIds.size
            kept += plan.keptLocalIds.size
        }

        private suspend fun save(query: RemoteQuery, scan: ScanState) {
            state = state.copy(scans = state.scans + (query.key to scan))
            stateStore.write(owner, state)
        }
    }
}

/**
 * Local changes a download must not overwrite: master-data changes still uploading, and rows
 * changed on this device after a page was fetched (whose server copy in that page is older).
 * Unsynced marks are also protected by their own `synced` flag in Room.
 */
internal class LocalChangeTracker {
    private val lock = Any()
    private var clock = 0L
    private val inFlight = HashMap<String, Int>()
    private val touchedAt = HashMap<String, Long>()

    /** Taken before fetching a page; compare with [busySince]. */
    fun token(): Long = synchronized(lock) { clock }

    /** [ids] in [collection] were just changed on this device. */
    fun touch(collection: SyncCollection, ids: Collection<String>) = synchronized(lock) {
        clock++
        ids.forEach { touchedAt[key(collection, it)] = clock }
    }

    /** Runs a local-first change; its rows count as busy until it finishes (uploaded or undone). */
    suspend fun <T> tracking(changes: List<Pair<SyncCollection, String>>, block: suspend () -> T): T {
        begin(changes)
        try {
            return block()
        } finally {
            end(changes)
        }
    }

    /** Ids in [collection] still being changed, or changed after [token] was taken. */
    fun busySince(token: Long, collection: SyncCollection): Set<String> = synchronized(lock) {
        val prefix = "${collection.name}:"
        (inFlight.keys.asSequence() + touchedAt.filterValues { it > token }.keys.asSequence())
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .toSet()
    }

    /** After a run: every later page takes a newer token, so older touches no longer matter. */
    fun forgetTouches() = synchronized(lock) { touchedAt.clear() }

    private fun begin(changes: List<Pair<SyncCollection, String>>) = synchronized(lock) {
        clock++
        changes.forEach { (collection, id) ->
            val k = key(collection, id)
            inFlight[k] = (inFlight[k] ?: 0) + 1
            touchedAt[k] = clock
        }
    }

    private fun end(changes: List<Pair<SyncCollection, String>>) = synchronized(lock) {
        clock++
        changes.forEach { (collection, id) ->
            val k = key(collection, id)
            val left = (inFlight[k] ?: 1) - 1
            if (left <= 0) inFlight.remove(k) else inFlight[k] = left
            touchedAt[k] = clock
        }
    }

    private fun key(collection: SyncCollection, id: String) = "${collection.name}:$id"
}
