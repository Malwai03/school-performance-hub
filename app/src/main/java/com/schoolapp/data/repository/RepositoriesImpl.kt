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
import com.schoolapp.core.logic.GradingEngine
import com.schoolapp.core.logic.GradingPolicy
import com.schoolapp.core.logic.SchoolScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.util.UUID

class AppContainer(context: Context) {
    private val db = SchoolDatabase.get(context)
    private val sessionStore = SessionStore(context)
    private val firebaseAuth: FirebaseAuth? = runCatching { FirebaseAuth.getInstance() }.getOrNull()
    private val firestore: FirebaseFirestore? = runCatching { FirebaseFirestore.getInstance() }.getOrNull()
    private val functions: FirebaseFunctions? = runCatching { Firebase.functions }.getOrNull()
    private val storage: FirebaseStorage? = runCatching { Firebase.storage }.getOrNull()

    val authRepository: AuthRepository = AuthRepositoryImpl(db, sessionStore, firebaseAuth, firestore, functions)
    val masterDataRepository: MasterDataRepository =
        MasterDataRepositoryImpl(db, firestore, sessionStore)
    val marksRepository: MarksRepository = MarksRepositoryImpl(db, firestore, functions, storage, sessionStore)
    val analyticsRepository: AnalyticsRepository = AnalyticsRepositoryImpl(db, firestore)

    suspend fun seedDemoDataIfNeeded() {
        withContext(Dispatchers.IO) {
            if (db.studentDao().countAll() > 0) return@withContext

            // Demo/seed data lives in the default school; demo logins are cached with it.
            val schoolId = SchoolScope.DEFAULT_SCHOOL_ID
            db.userDao().upsert(
                SchoolUser("admin-1", schoolId, UserRole.ADMIN, "Principal Admin", "admin001", true).toEntity(),
            )
            db.userDao().upsert(
                SchoolUser("teacher-1", schoolId, UserRole.TEACHER, "Teacher Maya", "teacher001", true).toEntity(),
            )
            db.userDao().upsert(
                SchoolUser("student-1", schoolId, UserRole.STUDENT, "Student Rohan", "student001", true).toEntity(),
            )

            val section = GradeSection("g6_a", schoolId, 6, "A")
            db.sectionDao().upsert(section.toEntity())

            val subjects = listOf(
                Subject("sub_math_6", schoolId, 6, "Mathematics", 33.0, true),
                Subject("sub_science_6", schoolId, 6, "Science", 33.0, true),
                Subject("sub_english_6", schoolId, 6, "English", 33.0, true),
            )
            subjects.forEach { db.subjectDao().upsert(it.toEntity()) }

            val students = listOf(
                Student("student-1", schoolId, section.sectionId, 1, "Rohan Das", true),
                Student("student-2", schoolId, section.sectionId, 2, "Aisha Noor", true),
                Student("student-3", schoolId, section.sectionId, 3, "Karan Shah", true),
            )
            students.forEach { db.studentDao().upsert(it.toEntity()) }

            db.teacherAssignmentDao().upsert(
                TeacherAssignment(
                    assignmentId = "assign-1",
                    schoolId = schoolId,
                    sectionId = section.sectionId,
                    subjectId = "sub_math_6",
                    teacherUid = "teacher-1",
                ).toEntity(),
            )

            val cycle = AssessmentCycle(
                cycleId = "cycle_2026_ut1",
                schoolId = schoolId,
                name = "Unit Test - March",
                startDateIso = "2026-03-01",
                endDateIso = "2026-03-15",
                published = false,
            )
            db.assessmentCycleDao().upsert(cycle.toEntity())

            val assessments = listOf(
                Assessment("assess_math_ut1", cycle.cycleId, section.sectionId, "sub_math_6", "Math Unit Test", 100.0, 1.0),
                Assessment("assess_science_ut1", cycle.cycleId, section.sectionId, "sub_science_6", "Science Unit Test", 100.0, 1.0),
                Assessment("assess_english_ut1", cycle.cycleId, section.sectionId, "sub_english_6", "English Unit Test", 100.0, 1.0),
            )
            assessments.forEach { db.assessmentDao().upsert(it.toEntity()) }

            val demoScores = listOf(
                ScoreEntry("score_1", "assess_math_ut1", "student-1", 78.0, com.schoolapp.core.model.ScoreStatus.PRESENT),
                ScoreEntry("score_2", "assess_science_ut1", "student-1", 72.0, com.schoolapp.core.model.ScoreStatus.PRESENT),
                ScoreEntry("score_3", "assess_english_ut1", "student-1", 85.0, com.schoolapp.core.model.ScoreStatus.PRESENT),
                ScoreEntry("score_4", "assess_math_ut1", "student-2", 91.0, com.schoolapp.core.model.ScoreStatus.PRESENT),
                ScoreEntry("score_5", "assess_science_ut1", "student-2", 88.0, com.schoolapp.core.model.ScoreStatus.PRESENT),
                ScoreEntry("score_6", "assess_english_ut1", "student-2", 84.0, com.schoolapp.core.model.ScoreStatus.PRESENT),
            )
            demoScores.forEach { db.scoreDao().upsert(it.toEntity(synced = true)) }
        }
    }
}

private class AuthRepositoryImpl(
    private val db: SchoolDatabase,
    private val sessionStore: SessionStore,
    private val auth: FirebaseAuth?,
    private val firestore: FirebaseFirestore?,
    private val functions: FirebaseFunctions?,
) : AuthRepository {

    override suspend fun signIn(loginCode: String, password: String): SchoolUser {
        return withContext(Dispatchers.IO) {
            require(loginCode.isNotBlank()) { "Login code is required" }
            require(password.isNotBlank()) { "Password is required" }

            val localUser = db.userDao().getByCode(loginCode)
            if (localUser != null && password == "pass123") {
                sessionStore.saveUserId(localUser.uid)
                return@withContext localUser.toDomain()
            }

            val firebaseAuth = auth ?: throw IllegalStateException(
                "Firebase auth not configured. Use demo login (pass123) or add google-services.json.",
            )
            val firebaseStore = firestore ?: throw IllegalStateException(
                "Firebase firestore not configured. Use demo login (pass123) or add google-services.json.",
            )

            val email = "${loginCode.lowercase()}@schoolapp.local"
            val authResult = firebaseAuth.signInWithEmailAndPassword(email, password).await()
            val firebaseUser = authResult.user ?: error("Authentication failed")
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
            db.userDao().upsert(user.toEntity())
            sessionStore.saveUserId(uid)
            user
        }
    }

    override suspend fun signOut() {
        withContext(Dispatchers.IO) {
            auth?.signOut()
            sessionStore.clear()
        }
    }

    override suspend fun currentUser(): SchoolUser? {
        return withContext(Dispatchers.IO) {
            val uid = sessionStore.getUserId() ?: auth?.currentUser?.uid ?: return@withContext null
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
}

private class MasterDataRepositoryImpl(
    private val db: SchoolDatabase,
    private val firestore: FirebaseFirestore?,
    private val sessionStore: SessionStore,
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
            val schoolId = currentSchoolId()
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
            val schoolId = currentSchoolId()
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
) : MarksRepository {

    override suspend fun upsertScore(score: ScoreEntry) {
        withContext(Dispatchers.IO) {
            val existing = db.scoreDao().getByAssessmentAndStudent(score.assessmentId, score.studentId)
            val canonicalId = existing.firstOrNull()?.scoreId ?: score.scoreId
            val canonicalScore = score.copy(scoreId = canonicalId)
            db.scoreDao().upsert(canonicalScore.toEntity(synced = false))
            db.scoreDao().deleteDuplicates(score.assessmentId, score.studentId, canonicalId)
            if (firestore == null) {
                db.scoreDao().markSynced(canonicalId)
                return@withContext
            }
            runCatching {
                val remote = firestore
                val schoolId = currentSchoolId()
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
