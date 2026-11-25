package io.github.tbeimu.gongxiaozhu.academic

import io.github.tbeimu.gongxiaozhu.network.NetworkDns
import java.net.InetAddress
import java.net.URLDecoder
import java.util.Base64
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HttpAcademicSessionTest {
    private val server = MockWebServer()
    private lateinit var client: OkHttpClient
    private lateinit var session: HttpAcademicSession
    private val requests = Collections.synchronizedList(mutableListOf<Request>())
    @Before fun setUp() {
        server.start()
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.port).build()).build())
        }.build()
        session = HttpAcademicSession(client, NetworkDns { listOf(InetAddress.getLoopbackAddress()) })
    }
    @After fun tearDown() {
        session.close(); client.dispatcher.cancelAll(); server.shutdown()
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }
    private fun response(html: String) = MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(html)
    private suspend fun open(): AcademicLoginPage {
        server.enqueue(response(academicLoginFixture()))
        return session.open()
    }
    private suspend fun loggedIn(): AcademicIdentity {
        val page = open()
        server.enqueue(response("<frameset><frame src='loging.aspx'></frameset>"))
        server.enqueue(response(academicGradeFixture()))
        return session.submit(page.token, "S209900001", "fictional-password", "AB12")
    }
    private fun take() = server.takeRequest(2, TimeUnit.SECONDS) ?: error("Expected fictional HTTP request")
    private fun fields(body: String) = body.split('&').associate { part ->
        val pair = part.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
    }

    @Test fun loginIsExplicitRsaEncryptedAndIdentityCheckedBeforeDataIsReturned() = runBlocking {
        val page = open()
        assertEquals(1, server.requestCount)
        assertEquals("AcademicLoginPage(<memory-only>)", page.toString())
        server.enqueue(response("<frameset><frame src='loging.aspx'></frameset>"))
        server.enqueue(response(academicGradeFixture()))
        val password = "  fake&+%中文=  "
        val identity = session.submit(page.token, " S209900001 ", password, "AB12")
        assertEquals("S209900001", identity.studentId)
        take()
        val form = fields(take().body.readUtf8())
        val decrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, academicTestKey.private) }
        val encrypted = form["ctl00" + '$' + "txtpassword"]!!
        assertNotEquals(password, encrypted)
        assertEquals(password, decrypt.doFinal(Base64.getDecoder().decode(encrypted)).toString(Charsets.UTF_8))
        assertEquals("fictional-state", form["__VIEWSTATE"])
        assertEquals("AB12", form["ctl00" + '$' + "txtyzm"])
        assertTrue(requests.all { it.url.scheme == "https" && it.url.host == "webrecdoc.bjut.edu.cn" })
        assertEquals(1, requests.count { it.method == "POST" })
        val grades = session.grades(99)
        assertEquals(99L, grades.updatedAt)
        assertEquals("91", grades.degree.single().grade)
        assertEquals(3, server.requestCount) // Authenticated response is reused, without another login or query.
    }

    @Test fun unrecognizedIdentityCannotBecomeASuccessfulLogin() = runBlocking {
        val page = open()
        server.enqueue(response("<frameset/>")); server.enqueue(response(academicGradeFixture(student = "S209900002")))
        assertEquals(AcademicProblem.ACCOUNT_MISMATCH, assertThrows(AcademicException::class.java) { runBlocking {
            session.submit(page.token, "S209900001", "fictional", "AB12")
        } }.problem)
        assertEquals(AcademicProblem.EXPIRED, assertThrows(AcademicException::class.java) { runBlocking { session.exams() } }.problem)
    }

    @Test fun oldLoginTokenCannotBeSubmittedTwiceAfterFailure() = runBlocking {
        val page = open(); server.enqueue(response(academicLoginFixture("next-state")))
        assertEquals(AcademicProblem.LOGIN, assertThrows(AcademicException::class.java) { runBlocking {
            session.submit(page.token, "S209900001", "fictional", "AB12")
        } }.problem)
        assertEquals(AcademicProblem.EXPIRED, assertThrows(AcademicException::class.java) { runBlocking {
            session.submit(page.token, "S209900001", "fictional", "AB12")
        } }.problem)
        assertEquals(2, server.requestCount)
    }

    @Test fun unavailableServerCannotReplayCredentialsEvenWithImmediateRetryHeader() = runBlocking {
        val page = open()
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(response(academicLoginFixture("must-not-be-read")))
        assertEquals(AcademicProblem.CONNECTION, assertThrows(AcademicException::class.java) { runBlocking {
            session.submit(page.token, "S209900001", "fictional", "AB12")
        } }.problem)
        assertEquals(2, server.requestCount)
    }

    @Test fun credentialPostNeverFollowsReplayRedirect() = runBlocking {
        val page = open()
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", ACADEMIC_ORIGIN + "/pyxx/login.aspx"))
        assertEquals(AcademicProblem.ADDRESS, assertThrows(AcademicException::class.java) { runBlocking {
            session.submit(page.token, "S209900001", "fictional", "AB12")
        } }.problem)
        assertEquals(2, server.requestCount)
    }

    @Test fun sameSchoolLegacyLoginDowngradeIsUpgradedBeforeAnyRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://webrecdoc.bjut.edu.cn:80/pyxx/login.aspx"))
        server.enqueue(response(academicLoginFixture()))
        assertNotNull(session.open())
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.scheme == "https" && it.method == "GET" })
    }

    @Test fun foreignRedirectIsRejectedWithoutFollowingIt() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/login"))
        assertEquals(AcademicProblem.ADDRESS, assertThrows(AcademicException::class.java) { runBlocking { session.open() } }.problem)
        assertEquals(1, server.requestCount)
    }

    @Test fun captchaRefreshInvalidatesOldTokenAndRefreshesFormState() = runBlocking {
        val old = open(); server.enqueue(response(academicLoginFixture("fresh-state")))
        val fresh = session.refreshCaptcha(old.token)
        assertNotEquals(old.token, fresh.token)
        assertEquals(AcademicProblem.EXPIRED, assertThrows(AcademicException::class.java) { runBlocking {
            session.submit(old.token, "S209900001", "fictional", "AB12")
        } }.problem)
        assertTrue(requests.all { it.method == "GET" })
    }

    @Test fun allExamPagesAreReadWithTheirCurrentViewState() = runBlocking {
        loggedIn()
        server.enqueue(response(academicExamFixture(pager = academicPager(6), state = "page-one-state")))
        server.enqueue(response(academicExamFixture(rows = examRow("第二场虚构考试"), state = "page-two-state")))
        val exams = session.exams()
        assertEquals(listOf("虚构考试", "第二场虚构考试"), exams.map { it.name })
        repeat(4) { take() }
        val posted = fields(take().body.readUtf8())
        assertEquals("page-one-state", posted["__VIEWSTATE"])
        assertEquals("ctl00" + '$' + "MainWork" + '$' + "dgData", posted["__EVENTTARGET"])
        assertEquals("Page" + '$' + "2", posted["__EVENTARGUMENT"])
    }

    private fun termOnlyExamFixture(pager: String = "") = academicExamFixture(pager = pager)
        .replace("option selected value=\"\"", "option value=\"\"")
        .replace("option value=\"20991\"", "option selected value=\"20991\"")

    @Test fun examQueryExplicitlySelectsAllTermsAndChecksReturnedFilter() = runBlocking {
        loggedIn()
        server.enqueue(response(termOnlyExamFixture()))
        server.enqueue(response(academicExamFixture(rows = examRow("全部学期的虚构考试"))))
        assertEquals("全部学期的虚构考试", session.exams().single().name)
        repeat(4) { take() }
        val posted = fields(take().body.readUtf8())
        assertEquals("", posted["ctl00" + '$' + "MainWork" + '$' + "txtxq"])
        assertEquals("1", posted["ctl00" + '$' + "MainWork" + '$' + "btnSearch.x"])
    }

    @Test fun unchangedTermAfterSearchCannotBecomeACompleteExamSnapshot() = runBlocking {
        loggedIn()
        server.enqueue(response(termOnlyExamFixture())); server.enqueue(response(termOnlyExamFixture()))
        assertEquals(AcademicProblem.RESPONSE, assertThrows(AcademicException::class.java) { runBlocking { session.exams() } }.problem)
        assertEquals(5, server.requestCount)
    }

    @Test fun paginatedExamResponseCannotSilentlyReturnToOneTerm() = runBlocking {
        loggedIn()
        server.enqueue(response(academicExamFixture(pager = academicPager(6))))
        server.enqueue(response(termOnlyExamFixture()))
        assertEquals(AcademicProblem.RESPONSE, assertThrows(AcademicException::class.java) { runBlocking { session.exams() } }.problem)
        assertEquals(5, server.requestCount)
    }

    @Test fun incompleteExamPageFailsInsteadOfPretendingFirstPageIsComplete() = runBlocking {
        loggedIn()
        server.enqueue(response(academicExamFixture(pager = academicPager(6))))
        server.enqueue(response("<html>学校暂时维护</html>"))
        assertEquals(AcademicProblem.RESPONSE, assertThrows(AcademicException::class.java) { runBlocking { session.exams() } }.problem)
    }

    @Test fun gb2312PageIsDecodedFromDeclaredCharset() = runBlocking {
        loggedIn()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=gb2312")
            .setBody(okio.Buffer().write(academicExamFixture().replace("charset=\"utf-8\"", "charset=\"gb2312\"").toByteArray(charset("GB18030")))))
        assertEquals("虚构考试", session.exams().single().name)
    }

    @Test fun closeCancelsBoundDnsAndWaitsForItsCleanup() = runBlocking {
        session.close(); session.awaitClosed()
        val entered = CompletableDeferred<Unit>(); val cleaned = CompletableDeferred<Unit>()
        session = HttpAcademicSession(client, NetworkDns {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cleaned.complete(Unit) }
        })
        val pending = async { runCatching { session.open() } }
        entered.await(); session.close(); session.awaitClosed(); pending.await()
        assertTrue(cleaned.isCompleted)
        assertEquals(0, server.requestCount)
    }

    @Test fun gradePagingRestartsTheSecondGridFromFreshFormState() = runBlocking {
        val page = open()
        server.enqueue(response("<frameset/>"))
        server.enqueue(response(academicGradeFixture(degreePager = academicPager(4))))
        session.submit(page.token, "S209900001", "fictional", "AB12")
        server.enqueue(response(academicGradeFixture(degreeRows = gradeRow("第二门示例课程"))))
        server.enqueue(response(academicGradeFixture()))
        val grades = session.grades(100)
        assertEquals(listOf("示例课程", "第二门示例课程"), grades.degree.map { it.course })
        assertEquals(1, grades.elective.size)
        repeat(3) { take() }
        assertEquals("POST", take().method)
        assertEquals("GET", take().method)
    }

    @Test fun queryReturningLoginPageIsExpiredWithoutRepeatingCredentials() = runBlocking {
        loggedIn(); server.enqueue(response(academicLoginFixture()))
        assertEquals(AcademicProblem.EXPIRED, assertThrows(AcademicException::class.java) { runBlocking { session.exams() } }.problem)
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test fun changedLoginControlNamesStopBeforeAnyCredentialSubmission() = runBlocking {
        server.enqueue(response(academicLoginFixture().replace("name=\"ctl00" + '$' + "txtpassword\"", "name=\"unexpected-password\"")))
        assertEquals(AcademicProblem.RESPONSE, assertThrows(AcademicException::class.java) { runBlocking { session.open() } }.problem)
        assertTrue(requests.all { it.method == "GET" })
    }

    @Test fun passwordEncodingMatchesTheSchoolsUtf16CodeUnitAlgorithm() {
        val encrypted = encryptAcademicPassword(academicTestPublicKey, "\uD83D\uDE00")
        val decrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, academicTestKey.private) }
        assertArrayEquals(intArrayOf(0xed, 0xa0, 0xbd, 0xed, 0xb8, 0x80).map { it.toByte() }.toByteArray(),
            decrypt.doFinal(Base64.getDecoder().decode(encrypted)))
    }
}
