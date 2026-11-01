import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onDocumentUpdated, onDocumentWritten } from "firebase-functions/v2/firestore";
import * as logger from "firebase-functions/logger";
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, FieldValue, Timestamp } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { getMessaging } from "firebase-admin/messaging";
import { parse as parseCsv } from "csv-parse/sync";
import * as XLSX from "xlsx";

if (!getApps().length) {
  initializeApp();
}

const db = getFirestore();
const storage = getStorage();
const adminAuth = getAuth();

const REGION = "us-central1";
const SUBJECT_DEFAULT_THRESHOLD = 33;
const OVERALL_DEFAULT_THRESHOLD = 33;

type ScoreStatus = "PRESENT" | "ABSENT" | "EXEMPT";
type PassStatus = "PASS" | "FAIL" | "INCOMPLETE";
type RiskLevel = "LOW" | "MEDIUM" | "HIGH";

type SubjectDoc = {
  subjectId: string;
  passThresholdPct?: number;
  mandatory?: boolean;
};

type AssessmentDoc = {
  assessmentId: string;
  cycleId: string;
  sectionId: string;
  subjectId: string;
  maxMarks: number;
  weight?: number;
};

type ScoreDoc = {
  scoreId: string;
  assessmentId: string;
  studentId: string;
  obtainedMarks: number;
  status: ScoreStatus;
};

type ResultPayload = {
  subjectPercentages: Record<string, number>;
  overallPercentage: number | null;
  passStatus: PassStatus;
  riskLevel: RiskLevel;
};

function requireAdmin(auth: { token?: Record<string, unknown> } | null | undefined): void {
  if (!auth) {
    throw new HttpsError("unauthenticated", "Authentication required");
  }
  const role = String(auth.token?.role ?? "");
  if (role !== "ADMIN") {
    throw new HttpsError("permission-denied", "Admin role required");
  }
}

function round2(value: number): number {
  return Math.round(value * 100) / 100;
}

function normalizeAssessmentWeight(value: unknown): number {
  const weight = Number(value ?? 1);
  if (!Number.isFinite(weight)) {
    throw new HttpsError("failed-precondition", "Assessment weight must be finite");
  }
  return Math.max(weight, 0);
}

function hiddenEmailFromCode(loginCode: string): string {
  return `${loginCode.trim().toLowerCase()}@schoolapp.local`;
}

function generateTemporaryPassword(length = 10): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
  let output = "";
  for (let i = 0; i < length; i++) {
    output += alphabet[Math.floor(Math.random() * alphabet.length)];
  }
  return `${output}!`;
}

function computeSubjectPercentage(assessments: AssessmentDoc[], scoreByAssessment: Map<string, ScoreDoc>): { percentage: number; complete: boolean } {
  if (!assessments.length) {
    return { percentage: 0, complete: false };
  }

  let weightedTotal = 0;
  let weightTotal = 0;

  for (const assessment of assessments) {
    const score = scoreByAssessment.get(assessment.assessmentId);
    if (!score) {
      return { percentage: 0, complete: false };
    }

    let marks: number | null;
    switch (score.status) {
      case "PRESENT":
        marks = Math.max(0, Math.min(score.obtainedMarks, assessment.maxMarks));
        break;
      case "ABSENT":
        marks = 0;
        break;
      case "EXEMPT":
        marks = null;
        break;
      default:
        marks = 0;
    }

    if (marks === null || assessment.maxMarks <= 0) {
      continue;
    }

    const pct = (marks / assessment.maxMarks) * 100;
    const weight = normalizeAssessmentWeight(assessment.weight);
    weightedTotal += pct * weight;
    weightTotal += weight;
  }

  if (weightTotal <= 0) {
    return { percentage: 0, complete: false };
  }

  return { percentage: round2(weightedTotal / weightTotal), complete: true };
}

function deriveRisk(passStatus: PassStatus, overall: number | null, previousOverall: number | null): RiskLevel {
  if (passStatus === "FAIL" || overall === null) {
    return "HIGH";
  }
  const gap = overall - OVERALL_DEFAULT_THRESHOLD;
  const drop = previousOverall == null ? 0 : Math.max(0, previousOverall - overall);

  if (gap < 3 || drop >= 12) return "HIGH";
  if (gap < 8 || drop >= 6) return "MEDIUM";
  return "LOW";
}

function computeResult(
  subjects: SubjectDoc[],
  assessments: AssessmentDoc[],
  scores: ScoreDoc[],
  previousOverall: number | null,
): ResultPayload {
  const scoreByAssessment = new Map<string, ScoreDoc>();
  for (const score of scores) {
    scoreByAssessment.set(score.assessmentId, score);
  }

  const assessmentsBySubject = new Map<string, AssessmentDoc[]>();
  for (const assessment of assessments) {
    const current = assessmentsBySubject.get(assessment.subjectId) ?? [];
    current.push(assessment);
    assessmentsBySubject.set(assessment.subjectId, current);
  }

  const subjectPercentages: Record<string, number> = {};
  let hasIncomplete = false;
  let hasFailedSubject = false;

  for (const subject of subjects.filter((s) => s.mandatory !== false)) {
    const result = computeSubjectPercentage(assessmentsBySubject.get(subject.subjectId) ?? [], scoreByAssessment);
    if (!result.complete) {
      hasIncomplete = true;
      continue;
    }
    subjectPercentages[subject.subjectId] = result.percentage;
    const threshold = subject.passThresholdPct ?? SUBJECT_DEFAULT_THRESHOLD;
    if (result.percentage < threshold) {
      hasFailedSubject = true;
    }
  }

  const percentages = Object.values(subjectPercentages);
  const overall = percentages.length ? round2(percentages.reduce((acc, n) => acc + n, 0) / percentages.length) : null;

  let passStatus: PassStatus;
  if (hasIncomplete || overall === null) {
    passStatus = "INCOMPLETE";
  } else if (hasFailedSubject || overall < OVERALL_DEFAULT_THRESHOLD) {
    passStatus = "FAIL";
  } else {
    passStatus = "PASS";
  }

  const riskLevel = deriveRisk(passStatus, overall, previousOverall);
  return {
    subjectPercentages,
    overallPercentage: overall,
    passStatus,
    riskLevel,
  };
}

async function parseImportedRows(fileRef: string, type: "csv" | "xlsx"): Promise<Record<string, unknown>[]> {
  const bucket = storage.bucket();
  const [bytes] = await bucket.file(fileRef).download();

  if (type === "csv") {
    const rows = parseCsv(bytes, {
      columns: true,
      skip_empty_lines: true,
      trim: true,
    }) as Record<string, unknown>[];
    return rows;
  }

  const workbook = XLSX.read(bytes, { type: "buffer" });
  const firstSheetName = workbook.SheetNames[0];
  if (!firstSheetName) return [];
  const worksheet = workbook.Sheets[firstSheetName];
  return XLSX.utils.sheet_to_json<Record<string, unknown>>(worksheet, { defval: "" });
}

export const createManagedUserAccount = onCall({ region: REGION }, async (request) => {
  requireAdmin(request.auth);
  const schoolId = String(request.data?.schoolId || "").trim() || "school_001";
  const role = String(request.data?.role || "").trim().toUpperCase();
  const loginCode = String(request.data?.loginCode || "").trim().toLowerCase();
  const displayName = String(request.data?.displayName || "").trim();
  const studentIdRaw = String(request.data?.studentId || "").trim();
  const sectionId = String(request.data?.sectionId || "").trim();

  if (!loginCode || /\s/.test(loginCode)) {
    throw new HttpsError("invalid-argument", "Valid login code is required");
  }
  if (!displayName) {
    throw new HttpsError("invalid-argument", "Display name is required");
  }
  if (role !== "STUDENT" && role !== "TEACHER") {
    throw new HttpsError("invalid-argument", "Role must be STUDENT or TEACHER");
  }
  if (role === "STUDENT" && !studentIdRaw) {
    throw new HttpsError("invalid-argument", "studentId is required for student accounts");
  }

  const email = hiddenEmailFromCode(loginCode);
  const temporaryPassword = generateTemporaryPassword();
  const userUid = role === "STUDENT" ? studentIdRaw : undefined;

  const schoolRef = db.collection("schools").doc(schoolId);
  const usersRef = schoolRef.collection("users");
  const existingByCode = await usersRef.where("code", "==", loginCode).limit(1).get();
  if (!existingByCode.empty) {
    throw new HttpsError("already-exists", "Login code already exists");
  }

  let authUserUid: string;
  try {
    const created = await adminAuth.createUser({
      uid: userUid,
      email,
      password: temporaryPassword,
      displayName,
      disabled: false,
    });
    authUserUid = created.uid;
  } catch (error: any) {
    logger.error("createManagedUserAccount createUser failed", error);
    const code = String(error?.code || "");
    if (code.includes("email-already-exists") || code.includes("uid-already-exists")) {
      throw new HttpsError("already-exists", "Account already exists for this login code or student");
    }
    throw new HttpsError("internal", "Unable to create auth account");
  }

  await adminAuth.setCustomUserClaims(authUserUid, {
    role,
    schoolId,
  });

  await usersRef.doc(authUserUid).set(
    {
      uid: authUserUid,
      schoolId,
      role,
      displayName,
      code: loginCode,
      active: true,
      mustChangePassword: true,
      linkedStudentId: role === "STUDENT" ? studentIdRaw : null,
      sectionId: sectionId || null,
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    },
    { merge: true },
  );

  if (role === "STUDENT") {
    await schoolRef.collection("students").doc(studentIdRaw).set(
      {
        authUid: authUserUid,
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
  }

  return {
    loginCode,
    temporaryPassword,
    role,
    uid: authUserUid,
  };
});

export const resetManagedUserPassword = onCall({ region: REGION }, async (request) => {
  requireAdmin(request.auth);
  const schoolId = String(request.data?.schoolId || "").trim() || "school_001";
  const loginCode = String(request.data?.loginCode || "").trim().toLowerCase();
  if (!loginCode || /\s/.test(loginCode)) {
    throw new HttpsError("invalid-argument", "Valid login code is required");
  }

  const schoolRef = db.collection("schools").doc(schoolId);
  const userSnap = await schoolRef.collection("users").where("code", "==", loginCode).limit(1).get();
  if (userSnap.empty) {
    throw new HttpsError("not-found", "User not found for login code");
  }
  const doc = userSnap.docs[0];
  const data = doc.data();
  const role = String(data.role || "STUDENT").toUpperCase();

  const temporaryPassword = generateTemporaryPassword();
  try {
    await adminAuth.updateUser(doc.id, { password: temporaryPassword });
  } catch (error) {
    logger.error("resetManagedUserPassword updateUser failed", error);
    throw new HttpsError("internal", "Unable to reset password");
  }

  await doc.ref.set(
    {
      mustChangePassword: true,
      updatedAt: FieldValue.serverTimestamp(),
      passwordResetAt: FieldValue.serverTimestamp(),
    },
    { merge: true },
  );

  return {
    loginCode,
    temporaryPassword,
    role,
  };
});

function normalizeEntityRow(entityType: string, row: Record<string, unknown>): { id: string; payload: Record<string, unknown> } {
  if (entityType === "students") {
    const studentId = String(row.studentId || row.id || "").trim();
    const payload = {
      studentId,
      schoolId: String(row.schoolId || ""),
      sectionId: String(row.sectionId || ""),
      rollNumber: Number(row.rollNumber || 0),
      fullName: String(row.fullName || ""),
      active: String(row.active || "true").toLowerCase() !== "false",
      updatedAt: FieldValue.serverTimestamp(),
    };
    if (!studentId || !payload.sectionId || !payload.fullName) {
      throw new HttpsError("invalid-argument", "Invalid student row");
    }
    return { id: studentId, payload };
  }

  if (entityType === "sections") {
    const sectionId = String(row.sectionId || row.id || "").trim();
    const payload = {
      sectionId,
      schoolId: String(row.schoolId || ""),
      grade: Number(row.grade || 1),
      sectionName: String(row.sectionName || ""),
      updatedAt: FieldValue.serverTimestamp(),
    };
    if (!sectionId || !payload.sectionName) {
      throw new HttpsError("invalid-argument", "Invalid section row");
    }
    return { id: sectionId, payload };
  }

  if (entityType === "subjects") {
    const subjectId = String(row.subjectId || row.id || "").trim();
    const payload = {
      subjectId,
      schoolId: String(row.schoolId || ""),
      grade: Number(row.grade || 1),
      name: String(row.name || ""),
      passThresholdPct: Number(row.passThresholdPct || SUBJECT_DEFAULT_THRESHOLD),
      mandatory: String(row.mandatory || "true").toLowerCase() !== "false",
      updatedAt: FieldValue.serverTimestamp(),
    };
    if (!subjectId || !payload.name) {
      throw new HttpsError("invalid-argument", "Invalid subject row");
    }
    return { id: subjectId, payload };
  }

  if (entityType === "scores") {
    const scoreId = String(row.scoreId || row.id || "").trim();
    const payload = {
      scoreId,
      assessmentId: String(row.assessmentId || ""),
      studentId: String(row.studentId || ""),
      obtainedMarks: Number(row.obtainedMarks || 0),
      status: String(row.status || "PRESENT"),
      updatedAt: FieldValue.serverTimestamp(),
    };
    if (!scoreId || !payload.assessmentId || !payload.studentId) {
      throw new HttpsError("invalid-argument", "Invalid score row");
    }
    return { id: scoreId, payload };
  }

  throw new HttpsError("invalid-argument", `Unsupported entity type: ${entityType}`);
}

async function writeImportedEntities(schoolId: string, entityType: string, rows: Record<string, unknown>[]): Promise<{ imported: number }> {
  const collection = entityType;
  const chunks: Array<Array<{ id: string; payload: Record<string, unknown> }>> = [];
  let pending: Array<{ id: string; payload: Record<string, unknown> }> = [];

  for (const row of rows) {
    pending.push(normalizeEntityRow(entityType, row));
    if (pending.length === 350) {
      chunks.push(pending);
      pending = [];
    }
  }
  if (pending.length) chunks.push(pending);

  for (const chunk of chunks) {
    const batch = db.batch();
    for (const item of chunk) {
      const ref = db.collection("schools").doc(schoolId).collection(collection).doc(item.id);
      batch.set(ref, item.payload, { merge: true });
    }
    await batch.commit();
  }

  await db.collection("schools").doc(schoolId).collection("imports").add({
    entityType,
    importedCount: rows.length,
    createdAt: Timestamp.now(),
  });

  return { imported: rows.length };
}

async function fetchAssessments(schoolId: string, cycleId: string, sectionId: string): Promise<AssessmentDoc[]> {
  const snap = await db
    .collection("schools")
    .doc(schoolId)
    .collection("assessments")
    .where("cycleId", "==", cycleId)
    .where("sectionId", "==", sectionId)
    .get();

  return snap.docs.map((doc) => ({
    assessmentId: doc.id,
    cycleId: String(doc.get("cycleId") || cycleId),
    sectionId: String(doc.get("sectionId") || sectionId),
    subjectId: String(doc.get("subjectId") || ""),
    maxMarks: Number(doc.get("maxMarks") || 0),
    weight: normalizeAssessmentWeight(doc.get("weight")),
  }));
}

async function fetchSubjects(schoolId: string, sectionId: string, subjectIds: string[]): Promise<SubjectDoc[]> {
  const schoolRef = db.collection("schools").doc(schoolId);
  const section = await schoolRef.collection("sections").doc(sectionId).get();
  const grade = section.get("grade");
  if (!section.exists || !Number.isInteger(grade) || grade <= 0) {
    throw new HttpsError("failed-precondition", "Section must have a valid grade to resolve required subjects");
  }

  // The grade curriculum is independent of which assessments have been created.
  const subjectsRef = schoolRef.collection("subjects");
  const roster = await subjectsRef.where("grade", "==", grade).get();
  const expectedIds = Array.from(new Set([...roster.docs.map((doc) => doc.id), ...subjectIds]));
  if (!expectedIds.length) return [];
  if (expectedIds.some((id) => !id)) {
    throw new HttpsError("failed-precondition", "Assessment must reference a subject");
  }
  const refs = expectedIds.map((id) => subjectsRef.doc(id));
  const docs = await db.getAll(...refs);
  return docs.map((doc) => {
    if (!doc.exists) {
      throw new HttpsError("failed-precondition", `Subject not found: ${doc.id}`);
    }
    return {
      subjectId: doc.id,
      passThresholdPct: Number(doc.get("passThresholdPct") || SUBJECT_DEFAULT_THRESHOLD),
      mandatory: Boolean(doc.get("mandatory") ?? true),
    };
  });
}

async function fetchScoresForAssessments(schoolId: string, assessmentIds: string[]): Promise<ScoreDoc[]> {
  if (!assessmentIds.length) return [];
  const result: ScoreDoc[] = [];

  for (let i = 0; i < assessmentIds.length; i += 10) {
    const chunk = assessmentIds.slice(i, i + 10);
    const snap = await db
      .collection("schools")
      .doc(schoolId)
      .collection("scores")
      .where("assessmentId", "in", chunk)
      .get();

    snap.docs.forEach((doc) => {
      result.push({
        scoreId: doc.id,
        assessmentId: String(doc.get("assessmentId") || ""),
        studentId: String(doc.get("studentId") || ""),
        obtainedMarks: Number(doc.get("obtainedMarks") || 0),
        status: String(doc.get("status") || "PRESENT") as ScoreStatus,
      });
    });
  }

  return result;
}

function academicStartDate(value: unknown): string | null {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const time = Date.parse(`${value}T00:00:00.000Z`);
  return Number.isFinite(time) && new Date(time).toISOString().slice(0, 10) === value ? value : null;
}

async function getPreviousOverall(schoolId: string, studentId: string, cycleId: string): Promise<number | null> {
  const schoolRef = db.collection("schools").doc(schoolId);
  const cyclesRef = schoolRef.collection("assessmentCycles");
  const current = await cyclesRef.doc(cycleId).get();
  // Academic order uses valid start dates, strictly earlier than the current cycle.
  // Cycle IDs and creation times do not establish chronology.
  const currentStart = academicStartDate(current.get("startDateIso"));
  if (!currentStart) return null;

  const snap = await schoolRef
    .collection("resultSnapshots")
    .where("studentId", "==", studentId)
    .get();

  // Only complete results from published cycles can establish a baseline.
  const candidates = snap.docs.filter((doc) => {
    const status = doc.get("passStatus");
    const overall = doc.get("overallPercentage");
    return doc.get("cycleId") && doc.get("cycleId") !== cycleId
      && (status === "PASS" || status === "FAIL")
      && typeof overall === "number" && Number.isFinite(overall);
  });
  if (!candidates.length) return null;

  const cycleIds = Array.from(new Set(candidates.map((doc) => String(doc.get("cycleId")))));
  const cycles = await db.getAll(...cycleIds.map((id) => cyclesRef.doc(id)));
  const earlierStarts = new Map<string, string>();
  for (const cycle of cycles) {
    const start = academicStartDate(cycle.get("startDateIso"));
    if (cycle.exists && cycle.get("published") === true && start && start < currentStart) {
      earlierStarts.set(cycle.id, start);
    }
  }

  let previousStart = "";
  let previousOverall: number | null = null;
  for (const candidate of candidates) {
    const start = earlierStarts.get(String(candidate.get("cycleId")));
    if (start && start > previousStart) {
      previousStart = start;
      previousOverall = candidate.get("overallPercentage");
    }
  }
  return previousOverall;
}

async function computeAndPersistStudentResult(
  schoolId: string,
  cycleId: string,
  sectionId: string,
  studentId: string,
): Promise<ResultPayload> {
  const assessments = await fetchAssessments(schoolId, cycleId, sectionId);
  const subjectIds = Array.from(new Set(assessments.map((a) => a.subjectId)));
  const subjects = await fetchSubjects(schoolId, sectionId, subjectIds);

  const assessmentIds = assessments.map((a) => a.assessmentId);
  const scoreDocs = await fetchScoresForAssessments(schoolId, assessmentIds);
  const studentScores = scoreDocs.filter((score) => score.studentId === studentId);
  const previous = await getPreviousOverall(schoolId, studentId, cycleId);

  const result = computeResult(subjects, assessments, studentScores, previous);
  const snapshotId = `${cycleId}_${sectionId}_${studentId}`;

  await db
    .collection("schools")
    .doc(schoolId)
    .collection("resultSnapshots")
    .doc(snapshotId)
    .set(
      {
        snapshotId,
        cycleId,
        sectionId,
        studentId,
        subjectPercentages: result.subjectPercentages,
        overallPercentage: result.overallPercentage,
        passStatus: result.passStatus,
        riskLevel: result.riskLevel,
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );

  return result;
}

async function computeAndPersistSectionResults(
  schoolId: string,
  cycleId: string,
  sectionId: string,
): Promise<{
  sectionId: string;
  cycleId: string;
  passCount: number;
  failCount: number;
  incompleteCount: number;
  averagePct: number;
  topperStudentId: string | null;
  riskDistribution: Record<string, number>;
}> {
  const studentsSnap = await db
    .collection("schools")
    .doc(schoolId)
    .collection("students")
    .where("sectionId", "==", sectionId)
    .where("active", "==", true)
    .get();

  let passCount = 0;
  let failCount = 0;
  let incompleteCount = 0;
  let topperStudentId: string | null = null;
  let topperScore = -1;
  const riskDistribution = { LOW: 0, MEDIUM: 0, HIGH: 0 };
  const totals: number[] = [];

  for (const studentDoc of studentsSnap.docs) {
    const result = await computeAndPersistStudentResult(schoolId, cycleId, sectionId, studentDoc.id);

    if (result.passStatus === "PASS") passCount += 1;
    if (result.passStatus === "FAIL") failCount += 1;
    if (result.passStatus === "INCOMPLETE") incompleteCount += 1;

    riskDistribution[result.riskLevel] = (riskDistribution[result.riskLevel] ?? 0) + 1;

    if (typeof result.overallPercentage === "number") {
      totals.push(result.overallPercentage);
      if (result.overallPercentage > topperScore) {
        topperScore = result.overallPercentage;
        topperStudentId = studentDoc.id;
      }
    }
  }

  const averagePct = totals.length ? round2(totals.reduce((a, b) => a + b, 0) / totals.length) : 0;

  const summary = {
    sectionId,
    cycleId,
    passCount,
    failCount,
    incompleteCount,
    averagePct,
    topperStudentId,
    riskDistribution,
    updatedAt: FieldValue.serverTimestamp(),
  };

  await db
    .collection("schools")
    .doc(schoolId)
    .collection("sectionAnalytics")
    .doc(`${cycleId}_${sectionId}`)
    .set(summary, { merge: true });

  return {
    sectionId,
    cycleId,
    passCount,
    failCount,
    incompleteCount,
    averagePct,
    topperStudentId,
    riskDistribution,
  };
}

async function notifySectionPublish(schoolId: string, sectionId: string, cycleId: string): Promise<void> {
  const usersSnap = await db
    .collection("schools")
    .doc(schoolId)
    .collection("users")
    .where("role", "==", "STUDENT")
    .where("sectionId", "==", sectionId)
    .get();

  const tokens: string[] = [];
  usersSnap.docs.forEach((doc) => {
    const userTokens = doc.get("fcmTokens");
    if (Array.isArray(userTokens)) {
      userTokens.forEach((t) => {
        if (typeof t === "string" && t.length > 0) tokens.push(t);
      });
    }
  });

  if (!tokens.length) {
    return;
  }

  for (let i = 0; i < tokens.length; i += 500) {
    const chunk = tokens.slice(i, i + 500);
    await getMessaging().sendEachForMulticast({
      tokens: chunk,
      notification: {
        title: "New Result Published",
        body: `Your ${cycleId} evaluation is now available.`,
      },
      data: {
        schoolId,
        sectionId,
        cycleId,
      },
    });
  }
}

export const importCsv = onCall({ region: REGION }, async (request) => {
  requireAdmin(request.auth);

  const schoolId = String(request.data.schoolId || "");
  const entityType = String(request.data.entityType || "");
  const fileRef = String(request.data.fileRef || "");

  if (!schoolId || !entityType || !fileRef) {
    throw new HttpsError("invalid-argument", "schoolId, entityType and fileRef are required");
  }

  const rows = await parseImportedRows(fileRef, "csv");
  const result = await writeImportedEntities(schoolId, entityType, rows);
  logger.info("importCsv complete", { schoolId, entityType, imported: result.imported });
  return result;
});

export const importExcel = onCall({ region: REGION }, async (request) => {
  requireAdmin(request.auth);

  const schoolId = String(request.data.schoolId || "");
  const entityType = String(request.data.entityType || "");
  const fileRef = String(request.data.fileRef || "");

  if (!schoolId || !entityType || !fileRef) {
    throw new HttpsError("invalid-argument", "schoolId, entityType and fileRef are required");
  }

  const rows = await parseImportedRows(fileRef, "xlsx");
  const result = await writeImportedEntities(schoolId, entityType, rows);
  logger.info("importExcel complete", { schoolId, entityType, imported: result.imported });
  return result;
});

export const publishCycleResults = onCall({ region: REGION }, async (request) => {
  requireAdmin(request.auth);

  const schoolId = String(request.data.schoolId || "");
  const cycleId = String(request.data.cycleId || "");
  const sectionId = String(request.data.sectionId || "");

  if (!schoolId || !cycleId || !sectionId) {
    throw new HttpsError("invalid-argument", "schoolId, cycleId and sectionId are required");
  }

  const summary = await computeAndPersistSectionResults(schoolId, cycleId, sectionId);

  await db
    .collection("schools")
    .doc(schoolId)
    .collection("assessmentCycles")
    .doc(cycleId)
    .set(
      {
        cycleId,
        published: true,
        publishedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );

  await notifySectionPublish(schoolId, sectionId, cycleId);
  return summary;
});

export const onScoreWriteRecompute = onDocumentWritten(
  {
    document: "schools/{schoolId}/scores/{scoreId}",
    region: REGION,
  },
  async (event) => {
    const after = event.data?.after;
    if (!after?.exists) {
      return;
    }

    const schoolId = event.params.schoolId;
    const score = after.data() as ScoreDoc;
    if (!score.assessmentId || !score.studentId) {
      return;
    }

    const assessmentDoc = await db
      .collection("schools")
      .doc(schoolId)
      .collection("assessments")
      .doc(score.assessmentId)
      .get();

    if (!assessmentDoc.exists) {
      logger.warn("Assessment not found for score", { schoolId, scoreId: event.params.scoreId });
      return;
    }

    const cycleId = String(assessmentDoc.get("cycleId") || "");
    const sectionId = String(assessmentDoc.get("sectionId") || "");
    if (!cycleId || !sectionId) {
      return;
    }

    await computeAndPersistStudentResult(schoolId, cycleId, sectionId, score.studentId);
  },
);

export const onPublishRecomputeAnalytics = onDocumentUpdated(
  {
    document: "schools/{schoolId}/assessmentCycles/{cycleId}",
    region: REGION,
  },
  async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();
    if (!after) return;

    const beforePublished = Boolean(before?.published);
    const afterPublished = Boolean(after.published);
    if (beforePublished || !afterPublished) {
      return;
    }

    const schoolId = event.params.schoolId;
    const cycleId = event.params.cycleId;

    const assessments = await db
      .collection("schools")
      .doc(schoolId)
      .collection("assessments")
      .where("cycleId", "==", cycleId)
      .get();

    const sectionIds = Array.from(
      new Set(assessments.docs.map((doc) => String(doc.get("sectionId") || "")).filter(Boolean)),
    );

    for (const sectionId of sectionIds) {
      await computeAndPersistSectionResults(schoolId, cycleId, sectionId);
      await notifySectionPublish(schoolId, sectionId, cycleId);
    }
  },
);

export const __testables = {
  fetchAssessments,
  getPreviousOverall,
  computeAndPersistStudentResult,
  computeSubjectPercentage,
  computeResult,
  deriveRisk,
};
