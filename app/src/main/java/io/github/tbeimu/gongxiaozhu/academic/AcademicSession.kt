package io.github.tbeimu.gongxiaozhu.academic

/** Only the challenge image and an opaque token leave the in-memory HTTP session. */
class AcademicLoginPage(val token: String, val captcha: ByteArray) {
    override fun toString() = "AcademicLoginPage(<memory-only>)"
}

interface AcademicSession {
    suspend fun open(): AcademicLoginPage
    suspend fun submit(token: String, username: String, password: String, captcha: String): AcademicIdentity
    suspend fun exams(): List<SchoolExam>
    suspend fun grades(now: Long): GradeSnapshot
    suspend fun refreshCaptcha(token: String): AcademicLoginPage
    fun close()
    /** close requests cancellation; awaitClosed also waits for native HTTP and DNS cleanup. */
    suspend fun awaitClosed() {}
}
