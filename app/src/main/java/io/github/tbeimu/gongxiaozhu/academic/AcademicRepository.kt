package io.github.tbeimu.gongxiaozhu.academic

import io.github.tbeimu.gongxiaozhu.data.VaultCipher
import io.github.tbeimu.gongxiaozhu.data.VaultStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val ACADEMIC_MAX_BYTES = 2 * 1024 * 1024
private const val ENVELOPE_BYTES = 4 + 12 + 16
private const val MAX_EXAMS = 2000
private const val MAX_GRADES_PER_GROUP = 5000
private val CREDIT_KEYS = setOf("学位学分", "选修学分", "已修学分", "应修学分")

/** A single encrypted identity, school snapshots, and local exam edits. Login state has no DTO here. */
class AcademicRepository(private val store: VaultStore, private val cipher: VaultCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<AcademicData?>(null)
    val state = mutable.asStateFlow()
    private val json = Json { encodeDefaults = true }
    private var existed = false

    suspend fun load(): AcademicData = locked {
        val loaded = storage { readDisk() }
        existed = loaded != null
        (loaded ?: AcademicData()).also { mutable.value = it }
    }

    suspend fun bind(identity: AcademicIdentity, expectedRevision: Long,
        isCurrent: () -> Boolean = { true }): AcademicData = locked {
        guard(isCurrent)
        val current = requireCurrent(expectedRevision)
        val normalized = incoming { normalizeIdentity(identity).also(::validateIdentity) }
        if (current.identity != null && current.identity.studentId != normalized.studentId)
            throw AcademicException(AcademicProblem.ACCOUNT_MISMATCH)
        val verified = normalized.copy(name = normalized.name.ifBlank { current.identity?.name.orEmpty() })
        persist(current.copy(identity = verified), isCurrent)
    }

    suspend fun saveExams(identity: AcademicIdentity, exams: List<SchoolExam>, updatedAt: Long,
        expectedRevision: Long? = null, isCurrent: () -> Boolean = { true }): AcademicData = locked {
        guard(isCurrent)
        val current = requireCurrent(expectedRevision)
        requireIdentity(current, identity)
        val originals = incoming {
            require(exams.size <= MAX_EXAMS && updatedAt > 0)
            exams.map { normalizeExam(it).also(::validateSchoolExam) }
        }
        val oldSchool = current.exams.filter { it.original != null }
        val oldGroups = oldSchool.groupBy { it.original!! }
        val newGroups = originals.groupingBy { it }.eachCount()
        val matched = mutableSetOf<String>()
        val refreshed = originals.map { original ->
            val previous = oldGroups[original]?.singleOrNull()?.takeIf { newGroups[original] == 1 }
            if (previous == null) ExamRecord(UUID.randomUUID().toString(), original, original.toExamFields())
            else previous.copy(original = original, retained = false).also { matched += it.id }
        }
        val preserved = oldSchool.filter { it.id !in matched && (it.modified || it.hidden) }
            .map { it.copy(retained = true) }
        val manual = current.exams.filter { it.original == null }
        persist(current.copy(exams = refreshed + preserved + manual, examUpdatedAt = updatedAt), isCurrent)
    }

    suspend fun saveGrades(identity: AcademicIdentity, grades: GradeSnapshot,
        isCurrent: () -> Boolean = { true }): AcademicData = locked {
        guard(isCurrent)
        val current = requireCurrent()
        requireIdentity(current, identity)
        val normalized = incoming { normalizeGrades(grades).also(::validateGrades) }
        persist(current.copy(grades = normalized), isCurrent)
    }

    suspend fun saveExam(id: String?, fields: ExamFields, expectedRevision: Long): AcademicData = locked {
        val current = requireCurrent(expectedRevision)
        val normalized = normalizeFields(fields)
        val existing = id?.let { requested -> current.exams.find { it.id == requested }
            ?: throw AcademicValidationException("考试记录已变化，请重新打开编辑") }
        validateEditedFields(normalized, existing?.original)
        val record = existing?.copy(fields = normalized) ?: ExamRecord(UUID.randomUUID().toString(), fields = normalized)
        val exams = if (id == null) current.exams + record else current.exams.map { if (it.id == id) record else it }
        if (exams.size > MAX_EXAMS) throw AcademicValidationException("考试记录已达上限，请先删除不需要的本地记录")
        persist(current.copy(exams = exams))
    }

    suspend fun deleteExam(id: String, expectedRevision: Long): AcademicData = locked {
        val current = requireCurrent(expectedRevision)
        val record = current.exams.find { it.id == id }
            ?: throw AcademicValidationException("考试记录已变化，请重新操作")
        val exams = if (record.original == null || record.retained) current.exams.filterNot { it.id == id }
            else current.exams.map { if (it.id == id) it.copy(hidden = true) else it }
        persist(current.copy(exams = exams))
    }

    suspend fun restoreExam(id: String, expectedRevision: Long): AcademicData = locked {
        val current = requireCurrent(expectedRevision)
        val record = current.exams.find { it.id == id }
            ?: throw AcademicValidationException("考试记录已变化，请重新操作")
        val original = record.original ?: throw AcademicValidationException("本地考试没有学校原始内容")
        persist(current.copy(exams = current.exams.map {
            if (it.id == id) it.copy(fields = original.toExamFields(), hidden = false) else it
        }))
    }

    suspend fun unbind(expectedRevision: Long): AcademicData = locked {
        val current = requireCurrent(expectedRevision)
        persist(current.copy(identity = null, exams = current.exams.filter { it.original == null },
            examUpdatedAt = null, grades = null))
    }

    /** An undo is valid only while both memory and the decrypted file still equal its after-state. */
    suspend fun restoreSnapshot(expected: AcademicData, before: AcademicData,
        isCurrent: () -> Boolean = { true }): AcademicData = locked {
        guard(isCurrent)
        val current = requireCurrent(expected.revision)
        if (current != expected || before.identity != expected.identity)
            throw AcademicValidationException("教务资料已变化，无法撤销此次操作")
        incoming { validateData(before) }
        persist(before.copy(revision = current.revision), isCurrent)
    }

    private suspend fun persist(value: AcademicData, isCurrent: () -> Boolean = { true }): AcademicData {
        guard(isCurrent)
        val previous = requireCurrent()
        val candidate = value.copy(revision = previous.revision)
        storage {
            val disk = readDisk()
            if (existed) require(disk == previous) else require(disk == null)
        }
        if (candidate == previous) return previous
        if (previous.revision == Long.MAX_VALUE) throw AcademicException(AcademicProblem.STORAGE)
        val next = candidate.copy(revision = previous.revision + 1)
        storage { validateData(next) }
        val plaintext = storage { json.encodeToString(next).encodeToByteArray() }
        try {
            if (plaintext.size > ACADEMIC_MAX_BYTES - ENVELOPE_BYTES) throw AcademicException(AcademicProblem.STORAGE)
            guard(isCurrent)
            val envelope = storage { cipher.encrypt(plaintext, requireExistingKey = existed) }
            if (envelope.size !in ENVELOPE_BYTES..ACADEMIC_MAX_BYTES) throw AcademicException(AcademicProblem.STORAGE)
            guard(isCurrent)
            storage {
                // Recheck after encryption too: an external writer must not be silently overwritten.
                val disk = readDisk()
                if (existed) require(disk == previous) else require(disk == null)
            }
            guard(isCurrent)
            storage { store.write(envelope) }
            existed = true
            mutable.value = next
            return next
        } finally { plaintext.fill(0) }
    }

    private fun readDisk(): AcademicData? {
        val envelope = store.read() ?: return null
        require(envelope.size in ENVELOPE_BYTES..ACADEMIC_MAX_BYTES)
        val plaintext = cipher.decrypt(envelope)
        return try {
            require(plaintext.size <= ACADEMIC_MAX_BYTES - ENVELOPE_BYTES)
            json.decodeFromString<AcademicData>(plaintext.decodeToString(throwOnInvalidSequence = true)).also(::validateData)
        } finally { plaintext.fill(0) }
    }

    private fun requireCurrent(expectedRevision: Long? = null): AcademicData {
        val current = mutable.value ?: throw AcademicException(AcademicProblem.STORAGE)
        if (expectedRevision != null && current.revision != expectedRevision)
            throw AcademicValidationException("教务资料已变化，请重新操作")
        return current
    }

    private fun requireIdentity(current: AcademicData, identity: AcademicIdentity) {
        incoming { validateIdentity(normalizeIdentity(identity)) }
        val bound = current.identity ?: throw AcademicException(AcademicProblem.IDENTITY)
        if (bound.studentId != identity.studentId.trim()) throw AcademicException(AcademicProblem.ACCOUNT_MISMATCH)
    }

    private suspend fun guard(isCurrent: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Academic operation retired")
    }

    private suspend fun <T> locked(action: suspend () -> T): T = mutex.withLock { withContext(ioDispatcher) { action() } }
    private inline fun <T> storage(action: () -> T): T = try { action() }
    catch (error: CancellationException) { throw error }
    catch (error: AcademicException) { throw error }
    catch (_: Exception) { throw AcademicException(AcademicProblem.STORAGE) }
    private inline fun <T> incoming(action: () -> T): T = try { action() }
    catch (error: CancellationException) { throw error }
    catch (error: AcademicException) { throw error }
    catch (_: Exception) { throw AcademicException(AcademicProblem.RESPONSE) }
}

private fun normalizeText(value: String) = value.replace('\u00a0', ' ').replace(Regex("[\t\r\n ]+"), " ").trim()
private fun normalizeIdentity(value: AcademicIdentity) = value.copy(studentId = value.studentId.trim(), name = normalizeText(value.name))
private fun normalizeExam(value: SchoolExam) = value.copy(code = normalizeText(value.code), name = normalizeText(value.name),
    className = normalizeText(value.className), start = normalizeText(value.start), end = normalizeText(value.end), location = normalizeText(value.location))
private fun normalizeFields(value: ExamFields) = value.copy(name = value.name.trim(), start = value.start.trim(),
    end = value.end.trim(), location = value.location.trim(), note = value.note.replace("\r\n", "\n").replace('\r', '\n').trim())
private fun normalizeGrades(value: GradeSnapshot) = value.copy(
    degree = value.degree.map(::normalizeGrade), elective = value.elective.map(::normalizeGrade),
    credits = value.credits.mapValues { normalizeText(it.value) }.toMap())
private fun normalizeGrade(value: GradeRecord) = value.copy(course = normalizeText(value.course), credits = normalizeText(value.credits),
    term = normalizeText(value.term), grade = normalizeText(value.grade))
private fun validText(value: String, maximum: Int, nonblank: Boolean = false, multiline: Boolean = false) =
    value.length <= maximum && (!nonblank || value.isNotBlank()) && value.none { it.isISOControl() && !(multiline && it == '\n') }
private fun validateIdentity(value: AcademicIdentity) {
    require(validText(value.studentId, 128, nonblank = true) && validText(value.name, 256))
}
private fun validTimes(start: String, end: String, original: SchoolExam? = null): Boolean {
    if (!validText(start, 64) || !validText(end, 64)) return false
    val from = parseAcademicExamTime(start)
    val to = parseAcademicExamTime(end)
    return (start.isEmpty() || from != null || (original != null && start == original.start)) &&
        (end.isEmpty() || to != null || (original != null && end == original.end)) &&
        (from == null || to == null || !to.isBefore(from))
}
private fun validSchoolTimes(start: String, end: String): Boolean {
    if (!validText(start, 64) || !validText(end, 64)) return false
    val from = parseAcademicExamTime(start)
    val to = parseAcademicExamTime(end)
    return from == null || to == null || !to.isBefore(from)
}
private fun validateSchoolExam(value: SchoolExam) {
    require(validText(value.code, 128) && validText(value.name, 300, nonblank = true) &&
        validText(value.className, 300) && validText(value.location, 500) && validSchoolTimes(value.start, value.end))
}
private fun validateFields(value: ExamFields, original: SchoolExam? = null) {
    require(validText(value.name, 300, nonblank = true) && validText(value.location, 500) &&
        validText(value.note, 2000, multiline = true) && validTimes(value.start, value.end, original))
}
private fun validateEditedFields(value: ExamFields, original: SchoolExam? = null) {
    if (value.name.isBlank()) throw AcademicValidationException("请填写考试名称")
    if (!validTimes(value.start, value.end, original)) throw AcademicValidationException("请填写有效的考试日期时间，结束时间不能早于开始时间")
    try { validateFields(value, original) } catch (_: IllegalArgumentException) {
        throw AcademicValidationException("考试内容过长或含不支持的字符，请调整后保存")
    }
}
private fun validateGrades(value: GradeSnapshot) {
    require(value.updatedAt > 0 && value.degree.size <= MAX_GRADES_PER_GROUP && value.elective.size <= MAX_GRADES_PER_GROUP)
    require(value.credits.size <= CREDIT_KEYS.size && value.credits.keys.all { it in CREDIT_KEYS } &&
        value.credits.values.all { validText(it, 128) })
    (value.degree + value.elective).forEach {
        require(validText(it.course, 300, nonblank = true) && validText(it.credits, 128) && validText(it.term, 128) && validText(it.grade, 128))
    }
}
private fun validateData(value: AcademicData) {
    require(value.schema == 1 && value.revision >= 0 && value.exams.size <= MAX_EXAMS)
    value.identity?.let(::validateIdentity)
    require(value.examUpdatedAt == null || value.examUpdatedAt > 0)
    require(value.identity != null || (value.exams.none { it.original != null } && value.grades == null && value.examUpdatedAt == null))
    require(value.exams.map { it.id }.distinct().size == value.exams.size)
    value.exams.forEach {
        require(runCatching { UUID.fromString(it.id) }.isSuccess)
        it.original?.let(::validateSchoolExam)
        validateFields(it.fields, it.original)
        require(it.original != null || (!it.retained && !it.hidden))
    }
    value.grades?.let(::validateGrades)
}
