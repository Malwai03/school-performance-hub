package com.schoolapp.data.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.schoolapp.core.logic.InboundMerge
import com.schoolapp.core.logic.InboundScope
import com.schoolapp.core.logic.LocalRow
import com.schoolapp.core.logic.ScanMode
import com.schoolapp.core.logic.ScanStateCodec
import com.schoolapp.core.logic.SyncCollection
import com.schoolapp.core.model.ResultSnapshot
import com.schoolapp.core.model.SchoolUser
import com.schoolapp.core.model.UserRole
import com.schoolapp.data.local.SchoolDatabase
import com.schoolapp.data.local.entity.AssessmentCycleEntity
import com.schoolapp.data.local.entity.AssessmentEntity
import com.schoolapp.data.local.entity.ScoreEntity
import com.schoolapp.data.local.entity.SectionEntity
import com.schoolapp.data.local.entity.StudentEntity
import com.schoolapp.data.local.entity.SubjectEntity
import com.schoolapp.data.local.entity.TeacherAssignmentEntity
import com.schoolapp.data.local.entity.toDomain
import com.schoolapp.data.local.entity.toEntity
import com.schoolapp.data.local.toJsonString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/** Firestore side of the download: always from the server, never the offline cache. */
internal class FirestoreInboundRemote(private val firestore: FirebaseFirestore) : InboundRemote {
    override suspend fun fetch(request: FetchRequest): List<RemoteDoc> {
        val spec = request.query
        val collection = firestore.collection("schools")
            .document(request.schoolId)
            .collection(spec.collection.path)
        // Exact ids are read as single documents: a rule that depends on the document id (a
        // student reading students/{their id}) is checked per document, not as a list query.
        if (spec.docIds != null) {
            return spec.docIds.mapNotNull { id ->
                collection.document(id).get(Source.SERVER).await().takeIf { it.exists() }?.let { doc ->
                    RemoteDoc(doc.id, doc.data.orEmpty(), doc.metadata.hasPendingWrites(), doc.updatedAtOrNull())
                }
            }
        }
        var query: Query = collection
        spec.equalTo.forEach { (field, value) -> query = query.whereEqualTo(field, value) }
        if (spec.inField != null) query = query.whereIn(spec.inField, spec.inValues)
        query = when (request.mode) {
            ScanMode.FULL -> query.orderBy(FieldPath.documentId())
                .let { ordered -> request.afterId?.let { ordered.startAfter(it) } ?: ordered }
            ScanMode.CHANGES -> query.orderBy("updatedAt").orderBy(FieldPath.documentId())
                .let { ordered ->
                    request.afterChange?.let { ordered.startAfter(Timestamp(it.seconds, it.nanos), it.docId) } ?: ordered
                }
        }
        // Not the offline cache: a partial cache must never look like "deleted on the server".
        val snapshot = query.limit(request.limit.toLong()).get(Source.SERVER).await()
        return snapshot.documents.map { doc ->
            RemoteDoc(
                id = doc.id,
                data = doc.data.orEmpty(),
                // This device's own queued write: the document shows the local change.
                pendingWrite = doc.metadata.hasPendingWrites(),
                updatedAt = doc.updatedAtOrNull(),
            )
        }
    }

    private fun DocumentSnapshot.updatedAtOrNull(): ServerTime? =
        runCatching { getTimestamp("updatedAt") }.getOrNull()?.let { ServerTime(it.seconds, it.nanoseconds) }
}

/** Room side of the download. Pages are applied by the caller's lock ([withOwnerLock]). */
internal class RoomInboundLocal(
    private val db: SchoolDatabase,
    private val withOwnerLock: suspend (suspend () -> Boolean) -> Boolean,
) : InboundLocal {

    override suspend fun facts(user: SchoolUser): InboundScope.ScopeFacts = when (user.role) {
        UserRole.ADMIN -> InboundScope.ScopeFacts()
        UserRole.TEACHER -> InboundScope.ScopeFacts(
            assignments = db.teacherAssignmentDao().getByTeacher(user.uid).map { it.toDomain() },
            assessments = db.assessmentDao().getAllRows().map { it.toDomain() },
        )
        UserRole.STUDENT -> {
            val student = db.studentDao().getById(user.uid)
            InboundScope.ScopeFacts(
                ownStudent = student?.toDomain(),
                ownSection = student?.let { db.sectionDao().getById(it.sectionId) }?.toDomain(),
            )
        }
    }

    override suspend fun withPage(
        collection: SyncCollection,
        selection: RowSelection,
        stillValid: suspend () -> Boolean,
        plan: (candidates: List<LocalRow>, dirty: List<LocalRow>) -> InboundMerge.Plan<Any>,
    ): Boolean = withOwnerLock {
        if (!stillValid()) {
            false
        } else {
            db.withTransaction {
                val decided = plan(rows(collection, selection), dirtyRows(collection))
                write(collection, decided)
            }
            true
        }
    }

    private suspend fun rows(collection: SyncCollection, selection: RowSelection): List<LocalRow> {
        val ids = (selection as? RowSelection.Ids)?.ids
        val range = selection as? RowSelection.IdRange
        suspend fun <E> pick(all: suspend () -> List<E>, byIds: suspend (List<String>) -> List<E>, inRange: suspend (String?, String?) -> List<E>): List<E> =
            when (selection) {
                RowSelection.None -> emptyList()
                RowSelection.All -> all()
                is RowSelection.Ids -> ids.orEmpty().chunked(MAX_SQL_ARGS).flatMap { byIds(it) }
                is RowSelection.IdRange -> inRange(range?.afterId, range?.throughId)
            }
        return when (collection) {
            SyncCollection.SECTIONS -> db.sectionDao().let { d -> pick(d::getAllRows, d::getByIds, d::getInIdRange).map { InboundDocs.localRow(it) } }
            SyncCollection.SUBJECTS -> db.subjectDao().let { d -> pick(d::getAllRows, d::getByIds, d::getInIdRange).map { InboundDocs.localRow(it) } }
            SyncCollection.STUDENTS -> db.studentDao().let { d -> pick(d::getAllRows, d::getByIds, d::getInIdRange).map { InboundDocs.localRow(it) } }
            SyncCollection.CYCLES -> db.assessmentCycleDao().let { d -> pick(d::getAllRows, d::getByIds, d::getInIdRange).map { InboundDocs.localRow(it) } }
            SyncCollection.ASSESSMENTS -> db.assessmentDao().let { d -> pick(d::getAllRows, d::getByIds, d::getInIdRange).map { InboundDocs.localRow(it) } }
            SyncCollection.ASSIGNMENTS -> db.teacherAssignmentDao().let { d -> pick(d::getAllRows, d::getByIds, d::getInIdRange).map { InboundDocs.localRow(it) } }
            SyncCollection.SCORES -> db.scoreDao().let { d -> pick(d::getAllRows, d::getByIds, d::getInIdRange).map { InboundDocs.localRow(it) } }
            SyncCollection.SNAPSHOTS -> db.resultSnapshotDao().let { d -> pick(d::getAllRows, d::getByIds, d::getInIdRange).map { InboundDocs.localRow(it) } }
        }
    }

    /** Rows whose local change the server has not confirmed: only unsynced marks are flagged in Room. */
    private suspend fun dirtyRows(collection: SyncCollection): List<LocalRow> =
        if (collection == SyncCollection.SCORES) db.scoreDao().getUnsynced().map { InboundDocs.localRow(it) } else emptyList()

    private suspend fun write(collection: SyncCollection, plan: InboundMerge.Plan<Any>) {
        val deletes = plan.deleteIds.chunked(MAX_SQL_ARGS)
        when (collection) {
            SyncCollection.SECTIONS -> db.sectionDao().run {
                upsertAll(plan.upserts.filterIsInstance<SectionEntity>()); deletes.forEach { deleteAllByIds(it) }
            }
            SyncCollection.SUBJECTS -> db.subjectDao().run {
                upsertAll(plan.upserts.filterIsInstance<SubjectEntity>()); deletes.forEach { deleteAllByIds(it) }
            }
            SyncCollection.STUDENTS -> db.studentDao().run {
                upsertAll(plan.upserts.filterIsInstance<StudentEntity>()); deletes.forEach { deleteAllByIds(it) }
            }
            SyncCollection.CYCLES -> db.assessmentCycleDao().run {
                upsertAll(plan.upserts.filterIsInstance<AssessmentCycleEntity>()); deletes.forEach { deleteAllByIds(it) }
            }
            SyncCollection.ASSESSMENTS -> db.assessmentDao().run {
                upsertAll(plan.upserts.filterIsInstance<AssessmentEntity>()); deletes.forEach { deleteAllByIds(it) }
            }
            SyncCollection.ASSIGNMENTS -> db.teacherAssignmentDao().run {
                upsertAll(plan.upserts.filterIsInstance<TeacherAssignmentEntity>()); deletes.forEach { deleteAllByIds(it) }
            }
            SyncCollection.SCORES -> db.scoreDao().run {
                upsertAll(plan.upserts.filterIsInstance<ScoreEntity>()); deletes.forEach { deleteAllByIds(it) }
            }
            SyncCollection.SNAPSHOTS -> db.resultSnapshotDao().run {
                upsertAll(plan.upserts.filterIsInstance<ResultSnapshot>().map { it.toEntity(it.subjectPercentages.toJsonString()) })
                deletes.forEach { deleteAllByIds(it) }
            }
        }
    }

    private companion object {
        /** Below SQLite's 999 bound arguments. */
        const val MAX_SQL_ARGS = 500
    }
}

private val Context.inboundSyncDataStore by preferencesDataStore(name = "inbound_sync")

/**
 * Download positions in DataStore, not Room: no schema change. Tagged with the account and
 * school they belong to, and cleared whenever the local data is cleared (LocalDataGuard).
 */
internal class DataStoreInboundState(private val context: Context) : InboundStateStore {
    private val ownerKey = stringPreferencesKey("owner")
    private val lastRunKey = longPreferencesKey("lastRunAt")

    override suspend fun read(owner: String): InboundSyncState {
        val prefs = context.inboundSyncDataStore.data.first()
        if (prefs[ownerKey] != owner) return InboundSyncState()
        val scans = prefs.asMap().entries
            .filter { it.key.name.startsWith(SCAN_PREFIX) }
            .associate { it.key.name.removePrefix(SCAN_PREFIX) to ScanStateCodec.decode(it.value as? String) }
        return InboundSyncState(scans = scans, lastRunAtMillis = prefs[lastRunKey])
    }

    override suspend fun write(owner: String, state: InboundSyncState) {
        context.inboundSyncDataStore.edit { prefs ->
            prefs.clear()
            prefs[ownerKey] = owner
            state.lastRunAtMillis?.let { prefs[lastRunKey] = it }
            state.scans.forEach { (key, scan) -> prefs[stringPreferencesKey(SCAN_PREFIX + key)] = ScanStateCodec.encode(scan) }
        }
    }

    /** Forget every position: the next run downloads from scratch. */
    suspend fun clear() {
        context.inboundSyncDataStore.edit { it.clear() }
    }

    /** The next automatic run goes ahead even if one ran recently (a new sign-in). */
    suspend fun markDue() {
        context.inboundSyncDataStore.edit { it.remove(lastRunKey) }
    }

    private companion object {
        const val SCAN_PREFIX = "scan:"
    }
}
