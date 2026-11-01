package com.schoolapp.data.repository

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ktx.storage
import com.schoolapp.BuildConfig
import com.schoolapp.core.model.Assessment
import com.schoolapp.core.model.AssessmentCycle
import com.schoolapp.core.model.BulkScorePatch
import com.schoolapp.core.model.GradeSection
import com.schoolapp.core.model.ManagedCredentialResult
import com.schoolapp.core.model.PassStatus
import com.schoolapp.core.model.PublishReadiness
import com.schoolapp.core.model.ResultSnapshot
import com.schoolapp.core.model.RiskLevel
import com.schoolapp.core.model.SchoolUser
import com.schoolapp.core.model.ScoreEntry
import com.schoolapp.core.model.SectionSummary
import com.schoolapp.core.model.Student
import com.schoolapp.core.model.Subject
import com.schoolapp.core.model.TeacherAssignment
import com.schoolapp.core.model.UserRole
import com.schoolapp.core.logic.DemoLogin
import com.schoolapp.core.logic.GradingEngine
import com.schoolapp.core.logic.GradingPolicy
import com.schoolapp.core.logic.LocalDataPolicy
import com.schoolapp.core.logic.SchoolScope
import com.schoolapp.core.logic.UnsyncedMarksChoice
import com.schoolapp.core.logic.TrendPoint
import com.schoolapp.data.local.toJsonString
import com.schoolapp.data.local.toSubjectMap
import com.schoolapp.data.local.SchoolDatabase
import com.schoolapp.data.local.entity.toDomain
import com.schoolapp.data.local.entity.toEntity
import com.schoolapp.domain.repository.AnalyticsRepository
import com.schoolapp.domain.repository.AuthRepository
import com.schoolapp.domain.repository.MarksRepository
import com.schoolapp.domain.repository.MasterDataRepository
import com.schoolapp.domain.repository.SignOutResult
import com.schoolapp.data.sync.ScoreUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import java.util.UUID

class AppContainer(context: Context) {
    private val db = SchoolDatabase.get(context)
    private val sessionStore = SessionStore(context)
    private val firebaseAuth: FirebaseAuth? = runCatching { FirebaseAuth.getInstance() }.getOrNull()
    private val firestore: FirebaseFirestore? = runCatching { FirebaseFirestore.getInstance() }.getOrNull()
    private val functions: FirebaseFunctions? = runCatching { Firebase.functions }.getOrNull()
    private val storage: FirebaseStorage? = runCatching { Firebase.storage }.getOrNull()

    /** Offline demo login (bundled seed users, pass123): debug builds only. */
    private val demoLoginEnabled: Boolean = BuildConfig.DEMO_LOGIN_ENABLED
    private val localData = LocalDataGuard(db, sessionStore, demoLoginEnabled)

    val authRepository: AuthRepository =
        AuthRepositoryImpl(db, sessionStore, localData, demoLoginEnabled, firebaseAuth, firestore, functions)
    val masterDataRepository: MasterDataRepository =
        MasterDataRepositoryImpl(db, firestore, sessionStore, firebaseAuth)
    val marksRepository: MarksRepository =
        MarksRepositoryImpl(db, firestore, functions, storage, sessionStore, firebaseAuth)
    val analyticsRepository: AnalyticsRepository = AnalyticsRepositoryImpl(db, firestore)

    /**
     * Startup housekeeping for the local data: forget a session that may not be restored,
     * record the owner of data left by older versions, seed the demo data in demo builds.
     */
    suspend fun prepareLocalData() {
        withContext(Dispatchers.IO) {
            localData.prepareAtStartup(firebaseUid = firebaseAuth?.currentUser?.uid)
        }
    }
}

private class AuthRepositoryImpl(
    private val db: SchoolDatabase,
    private val sessionStore: SessionStore,
    private val localData: LocalDataGuard,
    private val demoLoginEnabled: Boolean,
    private val auth: FirebaseAuth?,
    private val firestore: FirebaseFirestore?,
    private val functions: FirebaseFunctions?,
) : AuthRepository {

    override suspend fun signIn(
        loginCode: String,
        password: String,
        discardOtherAccountsUnsyncedMarks: Boolean,
    ): SchoolUser {
        return withContext(Dispatchers.IO) {
            require(loginCode.isNotBlank()) { "Login code is required" }
            require(password.isNotBlank()) { "Password is required" }

            // Offline demo login: only the bundled seed users, only in demo builds. It never
            // opens a real account; those always go through Firebase Auth below.
            val demoUid = DemoLogin.demoUidFor(loginCode, password, demoLoginEnabled)
            if (demoUid != null) {
                return@withContext signInDemo(demoUid, discardOtherAccountsUnsyncedMarks)
            }

            val firebaseAuth = auth ?: throw IllegalStateException(firebaseMissingMessage("auth"))
            val firebaseStore = firestore ?: throw IllegalStateException(firebaseMissingMessage("firestore"))

            val email = "${loginCode.lowercase()}@schoolapp.local"
            val authResult = firebaseAuth.signInWithEmailAndPassword(email, password).await()
            val firebaseUser = authResult.user ?: error("Authentication failed")
            try {
                val uid = firebaseUser.uid

                // The token's schoolId claim says which school this account belongs to.
                val claims = firebaseUser.getIdToken(false).await().claims
                val schoolId = SchoolScope.schoolIdForSignIn(claims)

                val profile = firebaseStore.collection("schools")
                    .document(schoolId)
                    .collection("users")
                    .document(uid)
                    .get()
                    .await()
                check(profile.exists()) { "No profile found for this account in its school" }

                val user = SchoolUser(
                    uid = uid,
                    schoolId = schoolId,
                    role = UserRole.valueOf(profile.getString("role") ?: UserRole.TEACHER.name),
                    displayName = profile.getString("displayName") ?: "User",
                    code = profile.getString("code") ?: loginCode,
                    active = profile.getBoolean("active") ?: true,
                )
                localData.withLock {
                    // Another account's local data goes before this account sees anything.
                    localData.claimForLocked(uid, discardOtherAccountsUnsyncedMarks)
                    db.userDao().upsert(user.toEntity())
                    sessionStore.saveUserId(uid)
                }
                user
            } catch (e: Throwable) {
                // Firebase stays signed in only together with an app session.
                firebaseAuth.signOut()
                throw e
            }
        }
    }

    private suspend fun signInDemo(demoUid: String, discardOtherAccountsUnsyncedMarks: Boolean): SchoolUser {
        // A demo session has no Firebase identity, so nothing done in it is uploaded as anyone.
        auth?.signOut()
        return localData.withLock {
            localData.claimForLocked(LocalDataPolicy.DEMO_OWNER, discardOtherAccountsUnsyncedMarks)
            localData.seedDemoIfFreeLocked()
            val user = db.userDao().getByUid(demoUid)?.takeIf { it.active }?.toDomain()
                ?: error("Demo account not found")
            sessionStore.saveUserId(demoUid)
            user
        }
    }

    private fun firebaseMissingMessage(service: String): String =
        if (demoLoginEnabled) {
            "Firebase $service not configured. Use a demo login or add google-services.json."
        } else {
            "Sign-in is unavailable: Firebase $service is not configured."
        }

    override suspend fun signOut(unsyncedMarks: UnsyncedMarksChoice): SignOutResult {
        return withContext(Dispatchers.IO) {
            localData.withLock {
                val owner = sessionStore.getDataOwner()
                val stillUnsynced = when {
                    owner == LocalDataPolicy.DEMO_OWNER -> 0
                    // Already asked after a failed upload: don't make them wait for another one.
                    unsyncedMarks != UnsyncedMarksChoice.ASK -> db.scoreDao().countUnsynced()
                    else -> uploadUnsyncedMarksBeforeSignOut()
                }
                when (val action = LocalDataPolicy.onSignOut(owner, stillUnsynced, unsyncedMarks)) {
                    is LocalDataPolicy.SignOutAction.AskUser ->
                        return@withLock SignOutResult.UnsyncedMarks(action.unsyncedMarks)
                    LocalDataPolicy.SignOutAction.ClearAndSignOut -> localData.clearLocked()
                    // Stays recorded as this account's: only it gets the data back on its next sign-in.
                    LocalDataPolicy.SignOutAction.SignOutKeepData -> Unit
                }
                auth?.signOut()
                sessionStore.clear()
                // Demo builds: fresh demo data for the next demo login.
                localData.seedDemoIfFreeLocked()
                SignOutResult.SignedOut
            }
        }
    }

    /**
     * One last upload of this account's marks while it is still signed in. Returns how many are
     * still not uploaded (offline, refused, timed out, or not this account's to upload).
     */
    private suspend fun uploadUnsyncedMarksBeforeSignOut(): Int {
        val pending = db.scoreDao().countUnsynced()
        if (pending == 0) return 0
        val remote = firestore ?: return pending
        val uid = sessionStore.getUserId() ?: return pending
        val schoolId = uploadSchoolIdOrNull(db, sessionStore, auth) ?: return pending
        // A Firestore write waits for the server, which never answers while offline.
        withTimeoutOrNull(FINAL_UPLOAD_TIMEOUT_MS) {
            ScoreUploader.uploadUnsynced(db, remote, schoolId) { auth?.currentUser?.uid == uid }
        }
        return db.scoreDao().countUnsynced()
    }

    override suspend fun currentUser(): SchoolUser? {
        return withContext(Dispatchers.IO) {
            val uid = sessionStore.getUserId() ?: return@withContext null
            val kind = LocalDataPolicy.sessionKind(uid, auth?.currentUser?.uid, demoLoginEnabled)
            if (kind == LocalDataPolicy.SessionKind.NONE) return@withContext null
            db.userDao().getByUid(uid)?.toDomain()
        }
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String) {
        withContext(Dispatchers.IO) {
            require(currentPassword.isNotBlank()) { "Current password is required" }
            require(newPassword.isNotBlank()) { "New password is required" }
            require(newPassword.length >= 6) { "New password must be at least 6 characters" }
            require(newPassword != currentPassword) { "New password must be different" }

            val firebaseAuth = auth ?: throw IllegalStateException(
                "Firebase Auth is not configured. Add google-services.json to enable password changes.",
            )
            val current = firebaseAuth.currentUser ?: throw IllegalStateException(
                "No Firebase user session found. Sign in using Firebase credentials first.",
            )
            val email = current.email ?: throw IllegalStateException("Current Firebase account has no email")
            val credential = EmailAuthProvider.getCredential(email, currentPassword)
            current.reauthenticate(credential).await()
            current.updatePassword(newPassword).await()
        }
    }

    override suspend fun createManagedAccount(
        role: UserRole,
        loginCode: String,
        displayName: String,
        studentId: String?,
        sectionId: String?,
    ): ManagedCredentialResult {
        return withContext(Dispatchers.IO) {
            require(role == UserRole.STUDENT || role == UserRole.TEACHER) {
                "Only student or teacher accounts can be created"
            }
            require(loginCode.isNotBlank()) { "Login code is required" }
            require(displayName.isNotBlank()) { "Display name is required" }
            if (role == UserRole.STUDENT) {
                require(!studentId.isNullOrBlank()) { "Student profile must be selected" }
            }

            val cloudFunctions = functions ?: throw IllegalStateException(
                "Firebase Functions not configured. Add google-services.json and deploy functions.",
            )
            val schoolId = currentSchoolId()
            val payload = mutableMapOf<String, Any>(
                "schoolId" to schoolId,
                "role" to role.name,
                "loginCode" to loginCode.trim(),
                "displayName" to displayName.trim(),
            )
            studentId?.takeIf { it.isNotBlank() }?.let { payload["studentId"] = it }
            sectionId?.takeIf { it.isNotBlank() }?.let { payload["sectionId"] = it }

            val result = cloudFunctions
                .getHttpsCallable("createManagedUserAccount")
                .call(payload)
                .await()
                .data as? Map<*, *>
                ?: error("Invalid response from createManagedUserAccount")

            ManagedCredentialResult(
                loginCode = (result["loginCode"] as? String)?.ifBlank { null }
                    ?: loginCode.trim(),
                temporaryPassword = result["temporaryPassword"] as? String
                    ?: error("Temporary password missing from response"),
                role = (result["role"] as? String)
                    ?.let { runCatching { UserRole.valueOf(it) }.getOrNull() }
                    ?: role,
            )
        }
    }

    override suspend fun resetManagedPassword(loginCode: String): ManagedCredentialResult {
        return withContext(Dispatchers.IO) {
            require(loginCode.isNotBlank()) { "Login code is required" }
            val cloudFunctions = functions ?: throw IllegalStateException(
                "Firebase Functions not configured. Add google-services.json and deploy functions.",
            )

            val result = cloudFunctions
                .getHttpsCallable("resetManagedUserPassword")
                .call(
                    mapOf(
                        "schoolId" to currentSchoolId(),
                        "loginCode" to loginCode.trim(),
                    ),
                )
                .await()
                .data as? Map<*, *>
                ?: error("Invalid response from resetManagedUserPassword")

            ManagedCredentialResult(
                loginCode = (result["loginCode"] as? String)?.ifBlank { null }
                    ?: loginCode.trim(),
                temporaryPassword = result["temporaryPassword"] as? String
                    ?: error("Temporary password missing from response"),
                role = (result["role"] as? String)
                    ?.let { runCatching { UserRole.valueOf(it) }.getOrNull() }
                    ?: UserRole.STUDENT,
            )
        }
    }

    private suspend fun currentSchoolId(): String =
        SchoolScope.requireSessionSchoolId(storedSessionSchoolId(db, sessionStore))

    private companion object {
        const val FINAL_UPLOAD_TIMEOUT_MS = 15_000L
    }
}

private class MasterDataRepositoryImpl(
    private val db: SchoolDatabase,
    private val firestore: FirebaseFirestore?,
    private val sessionStore: SessionStore,
    private val auth: FirebaseAuth?,
) : MasterDataRepository {

    override suspend fun upsertSection(section: GradeSection) {
        withContext(Dispatchers.IO) {
            db.sectionDao().upsert(section.toEntity())
            syncDoc("sections", section.sectionId, mapOf(
                "sectionId" to section.sectionId,
                "schoolId" to section.schoolId,
                "grade" to section.grade,
                "sectionName" to section.sectionName,
            ))
        }
    }

    override suspend fun upsertSubject(subject: Subject) {
        withContext(Dispatchers.IO) {
            db.subjectDao().upsert(subject.toEntity())
            syncDoc("subjects", subject.subjectId, mapOf(
                "subjectId" to subject.subjectId,
                "schoolId" to subject.schoolId,
                "grade" to subject.grade,
                "name" to subject.name,
                "passThresholdPct" to subject.passThresholdPct,
                "mandatory" to subject.mandatory,
            ))
        }
    }

    override suspend fun upsertStudent(student: Student) {
        withContext(Dispatchers.IO) {
            db.studentDao().upsert(student.toEntity())
            syncDoc("students", student.studentId, mapOf(
                "studentId" to student.studentId,
                "schoolId" to student.schoolId,
                "sectionId" to student.sectionId,
                "rollNumber" to student.rollNumber,
                "fullName" to student.fullName,
                "active" to student.active,
            ))
        }
    }

    override suspend fun deleteSection(sectionId: String) {
        withContext(Dispatchers.IO) {
            val section = db.sectionDao().getAll().firstOrNull { it.sectionId == sectionId } ?: return@withContext
            val assessments = db.assessmentDao().getBySection(sectionId)
            val assessmentIds = assessments.map { it.assessmentId }
            val students = db.studentDao().getBySectionAll(sectionId)

            if (assessmentIds.isNotEmpty()) {
                db.scoreDao().deleteByAssessmentIds(assessmentIds)
            }
            students.forEach { student ->
                db.scoreDao().deleteByStudent(student.studentId)
                db.resultSnapshotDao().deleteByStudent(student.studentId)
                db.studentDao().deleteById(student.studentId)
                deleteDoc("students", student.studentId)
            }

            db.resultSnapshotDao().deleteBySection(sectionId)
            db.assessmentDao().deleteBySection(sectionId)
            db.teacherAssignmentDao().deleteBySection(sectionId)
            db.sectionDao().deleteById(sectionId)

            assessments.forEach { assessment ->
                deleteDoc("assessments", assessment.assessmentId)
            }
            deleteDoc("sections", section.sectionId)
        }
    }

    override suspend fun deleteStudent(studentId: String) {
        withContext(Dispatchers.IO) {
            val student = db.studentDao().getById(studentId) ?: return@withContext
            db.scoreDao().deleteByStudent(studentId)
            db.resultSnapshotDao().deleteByStudent(studentId)
            db.studentDao().deleteById(studentId)
            deleteDoc("students", student.studentId)
        }
    }

    override suspend fun deleteSubject(subjectId: String) {
        withContext(Dispatchers.IO) {
            val subject = db.subjectDao().getById(subjectId) ?: return@withContext
            val assessments = db.assessmentDao().getBySubject(subjectId)
            val assessmentIds = assessments.map { it.assessmentId }
            if (assessmentIds.isNotEmpty()) {
                db.scoreDao().deleteByAssessmentIds(assessmentIds)
            }
            db.assessmentDao().deleteBySubject(subjectId)
            db.teacherAssignmentDao().deleteBySubject(subjectId)
            db.subjectDao().deleteById(subjectId)

            assessments.forEach { assessment -> deleteDoc("assessments", assessment.assessmentId) }
            deleteDoc("subjects", subject.subjectId)
        }
    }

    override suspend fun upsertAssessmentCycle(cycle: AssessmentCycle) {
        withContext(Dispatchers.IO) {
            db.assessmentCycleDao().upsert(cycle.toEntity())
            syncDoc("assessmentCycles", cycle.cycleId, mapOf(
                "cycleId" to cycle.cycleId,
                "schoolId" to cycle.schoolId,
                "name" to cycle.name,
                "startDateIso" to cycle.startDateIso,
                "endDateIso" to cycle.endDateIso,
                "published" to cycle.published,
            ))
        }
    }

    override suspend fun upsertAssessment(assessment: Assessment) {
        withContext(Dispatchers.IO) {
            db.assessmentDao().upsert(assessment.toEntity())
            syncDoc("assessments", assessment.assessmentId, mapOf(
                "assessmentId" to assessment.assessmentId,
                "cycleId" to assessment.cycleId,
                "sectionId" to assessment.sectionId,
                "subjectId" to assessment.subjectId,
                "title" to assessment.title,
                "maxMarks" to assessment.maxMarks,
                "weight" to assessment.weight,
            ))
        }
    }

    override suspend fun assignTeacher(sectionId: String, subjectId: String, teacherUid: String) {
        withContext(Dispatchers.IO) {
            val schoolId = currentSchoolId()
            val assignment = TeacherAssignment(
                assignmentId = "${sectionId}_${subjectId}_${teacherUid}",
                schoolId = schoolId,
                sectionId = sectionId,
                subjectId = subjectId,
                teacherUid = teacherUid,
            )
            db.teacherAssignmentDao().upsert(assignment.toEntity())
            syncDoc("teacherAssignments", assignment.assignmentId, mapOf(
                "assignmentId" to assignment.assignmentId,
                "schoolId" to assignment.schoolId,
                "sectionId" to assignment.sectionId,
                "subjectId" to assignment.subjectId,
                "teacherUid" to assignment.teacherUid,
            ))
        }
    }

    override suspend fun getSections(): List<GradeSection> {
        return withContext(Dispatchers.IO) {
            db.sectionDao().getAll().map { it.toDomain() }
        }
    }

    override suspend fun getSubjectsBySection(sectionId: String): List<Subject> {
        return withContext(Dispatchers.IO) {
            val section = db.sectionDao().getAll().firstOrNull { it.sectionId == sectionId } ?: return@withContext emptyList()
            db.subjectDao().getByGrade(section.grade).map { it.toDomain() }
        }
    }

    override suspend fun getStudentsBySection(sectionId: String): List<Student> {
        return withContext(Dispatchers.IO) {
            db.studentDao().getBySection(sectionId).map { it.toDomain() }
        }
    }

    override suspend fun getCycles(): List<AssessmentCycle> {
        return withContext(Dispatchers.IO) {
            db.assessmentCycleDao().getAll().map { it.toDomain() }
        }
    }

    override suspend fun getAssessments(sectionId: String, cycleId: String): List<Assessment> {
        return withContext(Dispatchers.IO) {
            db.assessmentDao().getBySectionAndCycle(sectionId, cycleId).map { it.toDomain() }
        }
    }

    private suspend fun syncDoc(collection: String, id: String, payload: Map<String, Any>) {
        runCatching {
            val remote = firestore ?: return@runCatching
            // Only as the signed-in account that owns the local data (never for demo data).
            val schoolId = uploadSchoolIdOrNull(db, sessionStore, auth) ?: return@runCatching
            remote.collection("schools")
                .document(schoolId)
                .collection(collection)
                .document(id)
                .set(payload)
                .await()
        }
    }

    private suspend fun deleteDoc(collection: String, id: String) {
        runCatching {
            val remote = firestore ?: return@runCatching
            val schoolId = uploadSchoolIdOrNull(db, sessionStore, auth) ?: return@runCatching
            remote.collection("schools")
                .document(schoolId)
                .collection(collection)
                .document(id)
                .delete()
                .await()
        }
    }

    private suspend fun currentSchoolId(): String =
        SchoolScope.requireSessionSchoolId(storedSessionSchoolId(db, sessionStore))
}

private class MarksRepositoryImpl(
    private val db: SchoolDatabase,
    private val firestore: FirebaseFirestore?,
    private val functions: FirebaseFunctions?,
    private val storage: FirebaseStorage?,
    private val sessionStore: SessionStore,
    private val auth: FirebaseAuth?,
) : MarksRepository {

    override suspend fun upsertScore(score: ScoreEntry) {
        withContext(Dispatchers.IO) {
            val existing = db.scoreDao().getByAssessmentAndStudent(score.assessmentId, score.studentId)
            val canonicalId = existing.firstOrNull()?.scoreId ?: score.scoreId
            val canonicalScore = score.copy(scoreId = canonicalId)
            db.scoreDao().upsert(canonicalScore.toEntity(synced = false))
            db.scoreDao().deleteDuplicates(score.assessmentId, score.studentId, canonicalId)
            if (firestore == null || sessionStore.getDataOwner() == LocalDataPolicy.DEMO_OWNER) {
                // Nothing to upload: no cloud configured, or demo data, which never leaves the device.
                db.scoreDao().markSynced(canonicalId)
                return@withContext
            }
            // Uploaded only as the account that owns the local marks; otherwise it stays
            // unsynced for that account (sync worker, or the final upload at sign-out).
            val schoolId = uploadSchoolIdOrNull(db, sessionStore, auth) ?: return@withContext
            runCatching {
                val remote = firestore
                remote.collection("schools")
                    .document(schoolId)
                    .collection("scores")
                    .document(canonicalId)
                    .set(
                        mapOf(
                            "scoreId" to canonicalScore.scoreId,
                            "assessmentId" to canonicalScore.assessmentId,
                            "studentId" to canonicalScore.studentId,
                            "obtainedMarks" to canonicalScore.obtainedMarks,
                            "status" to canonicalScore.status.name,
                        ),
                    )
                    .await()
                db.scoreDao().markSynced(canonicalId)
            }
        }
    }

    override suspend fun upsertScoresBulk(patch: BulkScorePatch) {
        withContext(Dispatchers.IO) {
            patch.rows.forEach { row ->
                upsertScore(row.copy(assessmentId = patch.assessmentId))
            }
        }
    }

    override suspend fun getScoresByAssessment(assessmentId: String): List<ScoreEntry> {
        return withContext(Dispatchers.IO) {
            db.scoreDao().getByAssessment(assessmentId).map { it.toDomain() }
        }
    }

    override suspend fun getScoreByAssessmentAndStudent(assessmentId: String, studentId: String): ScoreEntry? {
        return withContext(Dispatchers.IO) {
            db.scoreDao().getByAssessmentAndStudent(assessmentId, studentId).firstOrNull()?.toDomain()
        }
    }

    override suspend fun bulkImport(fileUri: String, type: String, entityType: String) {
        withContext(Dispatchers.IO) {
            val cloudFunctions = functions ?: throw IllegalStateException(
                "Firebase Functions not configured. Add google-services.json and deploy functions.",
            )
            val storageInstance = storage ?: throw IllegalStateException(
                "Firebase Storage not configured. Add google-services.json.",
            )
            val normalizedType = when (type.lowercase()) {
                "xlsx", "xls" -> "xlsx"
                else -> "csv"
            }
            val normalizedEntityType = entityType.lowercase()
            require(normalizedEntityType in setOf("students", "sections", "subjects", "scores")) {
                "Unsupported import entity: $entityType"
            }
            val callable = if (normalizedType == "xlsx") "importExcel" else "importCsv"
            val schoolId = currentSchoolId()
            val localUri = Uri.parse(fileUri)
            val extension = if (normalizedType == "xlsx") "xlsx" else "csv"
            val remotePath = "imports/$schoolId/${UUID.randomUUID()}.$extension"

            storageInstance.reference
                .child(remotePath)
                .putFile(localUri)
                .await()

            cloudFunctions
                .getHttpsCallable(callable)
                .call(
                    mapOf(
                        "schoolId" to schoolId,
                        "entityType" to normalizedEntityType,
                        "fileRef" to remotePath,
                    ),
                )
                .await()
        }
    }

    override suspend fun validatePublishReadiness(cycleId: String, sectionId: String): PublishReadiness {
        return withContext(Dispatchers.IO) {
            val assessments = db.assessmentDao().getBySectionAndCycle(sectionId, cycleId)
            if (assessments.isEmpty()) {
                return@withContext PublishReadiness(
                    ready = false,
                    missingEntries = 0,
                    totalRequiredEntries = 0,
                    message = "No assessments found for selected section and cycle",
                )
            }

            val students = db.studentDao().getBySection(sectionId)
            if (students.isEmpty()) {
                return@withContext PublishReadiness(
                    ready = false,
                    missingEntries = 0,
                    totalRequiredEntries = 0,
                    message = "No students found in selected section",
                )
            }

            val totalRequired = students.size * assessments.size
            var missingCount = 0
            assessments.forEach { assessment ->
                val scores = db.scoreDao().getByAssessment(assessment.assessmentId)
                    .groupBy { it.studentId }
                students.forEach { student ->
                    if (scores[student.studentId].isNullOrEmpty()) {
                        missingCount++
                    }
                }
            }

            val ready = missingCount == 0
            PublishReadiness(
                ready = ready,
                missingEntries = missingCount,
                totalRequiredEntries = totalRequired,
                message = if (ready) {
                    "Ready to publish"
                } else {
                    "$missingCount entries missing out of $totalRequired required scores"
                },
            )
        }
    }

    override suspend fun publishCycle(cycleId: String, sectionId: String) {
        withContext(Dispatchers.IO) {
            val cloudFunctions = functions
            if (cloudFunctions == null) {
                publishCycleLocally(cycleId, sectionId)
                return@withContext
            }
            val schoolId = currentSchoolId()
            cloudFunctions
                .getHttpsCallable("publishCycleResults")
                .call(
                    mapOf(
                        "schoolId" to schoolId,
                        "cycleId" to cycleId,
                        "sectionId" to sectionId,
                    ),
                )
                .await()
        }
    }

    private suspend fun publishCycleLocally(cycleId: String, sectionId: String) {
        val assessments = db.assessmentDao().getBySectionAndCycle(sectionId, cycleId).map { it.toDomain() }
        if (assessments.isEmpty()) return

        val subjectIds = assessments.map { it.subjectId }.distinct()
        val subjects = subjectIds.mapNotNull { subjectId -> db.subjectDao().getById(subjectId)?.toDomain() }
        val students = db.studentDao().getBySection(sectionId).map { it.toDomain() }
        val policy = GradingPolicy(defaultSubjectThresholdPct = 33.0, overallThresholdPct = 33.0)

        students.forEach { student ->
            val scores = assessments
                .flatMap { assessment ->
                db.scoreDao().getByAssessment(assessment.assessmentId).map { it.toDomain() }
                }
                .groupBy { it.assessmentId to it.studentId }
                .mapNotNull { (_, entries) -> entries.firstOrNull() }
                .filter { it.studentId == student.studentId }

            val previous = db.resultSnapshotDao().getStudentTrend(student.studentId)
                .map { it.toDomain(it.subjectPercentagesJson.toSubjectMap()) }
                .mapNotNull { it.overallPercentage }
                .lastOrNull()

            val trend = previous?.let { listOf(TrendPoint(cycleId = "prev", overallPct = it)) }.orEmpty()

            val snapshot = GradingEngine.buildResultSnapshot(
                snapshotId = "${cycleId}_${sectionId}_${student.studentId}",
                cycleId = cycleId,
                sectionId = sectionId,
                studentId = student.studentId,
                subjects = subjects,
                assessments = assessments,
                scores = scores,
                policy = policy,
                previousTrend = trend,
            )

            db.resultSnapshotDao().upsert(snapshot.toEntity(snapshot.subjectPercentages.toJsonString()))
        }

        val cycle = db.assessmentCycleDao().getById(cycleId)
        if (cycle != null) {
            db.assessmentCycleDao().upsert(cycle.copy(published = true))
        }
    }

    private suspend fun currentSchoolId(): String =
        SchoolScope.requireSessionSchoolId(storedSessionSchoolId(db, sessionStore))
}

private class AnalyticsRepositoryImpl(
    private val db: SchoolDatabase,
    private val firestore: FirebaseFirestore?,
) : AnalyticsRepository {

    override suspend fun getStudentTrend(studentId: String): List<ResultSnapshot> {
        return withContext(Dispatchers.IO) {
            val local = db.resultSnapshotDao().getStudentTrend(studentId)
            if (local.isNotEmpty()) {
                return@withContext local.map { it.toDomain(it.subjectPercentagesJson.toSubjectMap()) }
            }

            val remoteStore = firestore ?: return@withContext emptyList()
            val remote = runCatching {
                remoteStore.collectionGroup("resultSnapshots")
                    .whereEqualTo("studentId", studentId)
                    .get()
                    .await()
                    .documents
            }.getOrDefault(emptyList())

            remote.mapNotNull { doc ->
                val map = (doc.get("subjectPercentages") as? Map<*, *>)
                    ?.entries
                    ?.filter { it.key is String && it.value is Number }
                    ?.associate { it.key as String to (it.value as Number).toDouble() }
                    .orEmpty()

                val snapshot = ResultSnapshot(
                    snapshotId = doc.id,
                    cycleId = doc.getString("cycleId") ?: return@mapNotNull null,
                    sectionId = doc.getString("sectionId") ?: return@mapNotNull null,
                    studentId = doc.getString("studentId") ?: return@mapNotNull null,
                    subjectPercentages = map,
                    overallPercentage = doc.getDouble("overallPercentage"),
                    passStatus = PassStatus.valueOf(doc.getString("passStatus") ?: PassStatus.INCOMPLETE.name),
                    riskLevel = RiskLevel.valueOf(doc.getString("riskLevel") ?: RiskLevel.HIGH.name),
                )
                db.resultSnapshotDao().upsert(snapshot.toEntity(snapshot.subjectPercentages.toJsonString()))
                snapshot
            }.sortedBy { it.cycleId }
        }
    }

    override suspend fun getSectionSummary(cycleId: String, sectionId: String): SectionSummary {
        return withContext(Dispatchers.IO) {
            val snapshots = db.resultSnapshotDao().getSectionResults(sectionId, cycleId)
                .map { it.toDomain(it.subjectPercentagesJson.toSubjectMap()) }

            if (snapshots.isEmpty()) {
                return@withContext SectionSummary(
                    cycleId = cycleId,
                    sectionId = sectionId,
                    averagePct = 0.0,
                    passCount = 0,
                    failCount = 0,
                    incompleteCount = 0,
                    topperStudentId = null,
                    riskDistribution = mapOf(RiskLevel.LOW to 0, RiskLevel.MEDIUM to 0, RiskLevel.HIGH to 0),
                )
            }

            val passCount = snapshots.count { it.passStatus == PassStatus.PASS }
            val failCount = snapshots.count { it.passStatus == PassStatus.FAIL }
            val incompleteCount = snapshots.count { it.passStatus == PassStatus.INCOMPLETE }
            val avg = snapshots.mapNotNull { it.overallPercentage }.average().takeIf { !it.isNaN() } ?: 0.0
            val topper = snapshots.maxByOrNull { it.overallPercentage ?: -1.0 }?.studentId
            val risk = mapOf(
                RiskLevel.LOW to snapshots.count { it.riskLevel == RiskLevel.LOW },
                RiskLevel.MEDIUM to snapshots.count { it.riskLevel == RiskLevel.MEDIUM },
                RiskLevel.HIGH to snapshots.count { it.riskLevel == RiskLevel.HIGH },
            )

            SectionSummary(
                cycleId = cycleId,
                sectionId = sectionId,
                averagePct = ((avg * 100).roundToInt() / 100.0),
                passCount = passCount,
                failCount = failCount,
                incompleteCount = incompleteCount,
                topperStudentId = topper,
                riskDistribution = risk,
            )
        }
    }
}
