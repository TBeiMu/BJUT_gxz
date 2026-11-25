package io.github.tbeimu.gongxiaozhu.academic

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.serialization.Serializable

@Serializable
data class AcademicIdentity(val studentId: String, val name: String = "") {
    override fun toString() = "AcademicIdentity(<redacted>)"
}

/** Only the school's normalized table text; no inferred school record identifier. */
@Serializable
data class SchoolExam(val code: String, val name: String, val className: String,
    val start: String, val end: String, val location: String) {
    override fun toString() = "SchoolExam(<redacted>)"
}

@Serializable
data class ExamFields(val name: String, val start: String = "", val end: String = "",
    val location: String = "", val note: String = "") {
    override fun toString() = "ExamFields(<redacted>)"
}

@Serializable
data class ExamRecord(val id: String, val original: SchoolExam? = null, val fields: ExamFields,
    val retained: Boolean = false, val hidden: Boolean = false) {
    override fun toString() = "ExamRecord(data=<redacted>)"
}

fun SchoolExam.toExamFields() = ExamFields(name, start, end, location)
val ExamRecord.modified: Boolean get() = original?.let { fields != it.toExamFields() } ?: false

@Serializable
data class GradeRecord(val course: String, val credits: String, val term: String, val grade: String,
    val restricted: Boolean = false) {
    override fun toString() = "GradeRecord(<redacted>)"
}

@Serializable
data class GradeSnapshot(val degree: List<GradeRecord>, val elective: List<GradeRecord>,
    val credits: Map<String, String>, val updatedAt: Long) {
    override fun toString() = "GradeSnapshot(data=<redacted>)"
}

@Serializable
data class AcademicData(val schema: Int = 1, val identity: AcademicIdentity? = null,
    val exams: List<ExamRecord> = emptyList(), val examUpdatedAt: Long? = null,
    val grades: GradeSnapshot? = null, val revision: Long = 0) {
    override fun toString() = "AcademicData(schema=$schema, revision=$revision, data=<redacted>)"
}

enum class AcademicProblem(val hint: String) {
    CONNECTION("教务服务无法连接，请确认 WiFi 或移动数据可上网后手动重试"),
    LOGIN("学校登录未完成，请检查页面信息后重新提交"),
    EXPIRED("教务登录态已失效，请重新登录"),
    IDENTITY("未能确认学校返回的学号，已停止读取教务信息"),
    ACCOUNT_MISMATCH("学校返回的学号与已绑定教务账号不一致，请先解除教务绑定"),
    RESPONSE("教务响应无法识别，已保留原数据；请记录此提示以便适配"),
    ADDRESS("学校页面出现未支持的地址，已停止请求"),
    STORAGE("教务加密数据无法读取或写入，已保留原数据，请重试"),
    WEBVIEW("请更新系统 WebView 后再使用学校网页登录"),
}

class AcademicException(val problem: AcademicProblem) : Exception(problem.hint)
class AcademicValidationException(message: String) : Exception(message)

private val examTimeFormats = listOf(DateTimeFormatter.ISO_LOCAL_DATE_TIME,
    DateTimeFormatter.ofPattern("uuuu-MM-dd H:mm[:ss]").withResolverStyle(ResolverStyle.STRICT),
    DateTimeFormatter.ofPattern("uuuu/M/d H:mm[:ss]").withResolverStyle(ResolverStyle.STRICT))

/** A missing or unrecognized local date-time stays unknown; callers must not invent one. */
fun parseAcademicExamTime(value: String): LocalDateTime? = examTimeFormats.firstNotNullOfOrNull {
    runCatching { LocalDateTime.parse(value.trim(), it) }.getOrNull()
}
