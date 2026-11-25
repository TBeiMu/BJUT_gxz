package io.github.tbeimu.gongxiaozhu.academic

import io.github.tbeimu.gongxiaozhu.data.readBounded
import io.github.tbeimu.gongxiaozhu.network.NetworkDns
import java.io.IOException
import java.net.Proxy
import java.nio.charset.Charset
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val ACADEMIC_ORIGIN = "https://webrecdoc.bjut.edu.cn"
private const val LOGIN_PATH = "/pyxx/login.aspx"
private const val EXAMS_PATH = "/pyxx/pygl/pykscx.aspx"
private const val GRADES_PATH = "/pyxx/grgl/xskccjcx.aspx"
private const val CAPTCHA_PATH = "/pyxx/PageTemplate/NsoftPage/yzm/createyzm.aspx"
private const val USERNAME = "ctl00\$txtusername"
private const val PASSWORD = "ctl00\$txtpassword"
private const val CAPTCHA = "ctl00\$txtyzm"
private const val PUBLIC_KEY = "ctl00\$pubkey"
private const val SUBMIT = "ctl00\$ImageButton1"

internal fun allowedAcademicUrl(url: HttpUrl): Boolean = url.scheme == "https" && url.port == 443 &&
    url.host == "webrecdoc.bjut.edu.cn" && url.username.isEmpty() && url.password.isEmpty() &&
    url.encodedPath in setOf(LOGIN_PATH, EXAMS_PATH, GRADES_PATH, CAPTCHA_PATH, "/pyxx/default.aspx", "/pyxx/loging.aspx")

private class AcademicCookies : CookieJar {
    private val entries = mutableListOf<Cookie>()
    private var closed = false
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (closed) return
        cookies.forEach { cookie ->
            entries.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) entries += cookie
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        entries.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return if (closed) emptyList() else entries.filter { it.matches(url) }
    }
    @Synchronized fun clear() { closed = true; entries.clear() }
}

private class AcademicResponse(val url: HttpUrl, val bytes: ByteArray, val charset: Charset?) {
    fun document(): Document = Jsoup.parse(bytes.inputStream(), charset?.name(), url.toString())
}
private class AcademicForm(val url: HttpUrl, val hidden: Map<String, String>, val publicKey: String)

/** One independent, bounded physical-network session. No credentials or school HTML are persisted. */
class HttpAcademicSession(boundClient: OkHttpClient, private val dns: NetworkDns) : AcademicSession {
    private val closed = AtomicBoolean(false)
    private val cookies = AcademicCookies()
    private val client = boundClient.newBuilder().cookieJar(cookies).cache(null).proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(18, TimeUnit.SECONDS).build()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val operations = mutableSetOf<Job>()
    private val formLock = Any()
    private var pageToken: String? = null
    private var form: AcademicForm? = null
    @Volatile private var verified: AcademicIdentity? = null
    private var cachedGrades: AcademicResponse? = null

    override suspend fun open(): AcademicLoginPage = inSession {
        verified = null; cachedGrades = null
        loginPage(follow((ACADEMIC_ORIGIN + LOGIN_PATH).toHttpUrl()))
    }

    override suspend fun refreshCaptcha(token: String): AcademicLoginPage = inSession {
        synchronized(formLock) {
            if (pageToken != token || form == null) fail(AcademicProblem.EXPIRED)
            pageToken = null; form = null
        }
        // Reloading a fresh login form also refreshes its RSA key, view state and challenge together.
        loginPage(follow((ACADEMIC_ORIGIN + LOGIN_PATH).toHttpUrl()))
    }

    override suspend fun submit(token: String, username: String, password: String, captcha: String): AcademicIdentity = inSession {
        val account = username.trim()
        if (account.isEmpty() || account.length > 128 || password.isEmpty() || password.length > 512 ||
            captcha.trim().isEmpty() || captcha.trim().length > 32) fail(AcademicProblem.LOGIN)
        val current = synchronized(formLock) {
            if (pageToken != token || closed.get()) fail(AcademicProblem.EXPIRED)
            val value = form ?: fail(AcademicProblem.EXPIRED)
            pageToken = null; form = null
            value
        }
        val encrypted = encryptAcademicPassword(current.publicKey, password)
        val body = FormBody.Builder(Charsets.UTF_8).apply {
            current.hidden.forEach { (name, value) -> add(name, value) }
            add(USERNAME, account); add(PASSWORD, encrypted); add(CAPTCHA, captcha.trim())
            add(SUBMIT + ".x", "1"); add(SUBMIT + ".y", "1")
        }.build().oneShot()
        val submitted = follow(current.url, body, current.url)
        if (AcademicParser.isLogin(submitted.document())) fail(AcademicProblem.LOGIN)
        val gradePage = follow((ACADEMIC_ORIGIN + GRADES_PATH).toHttpUrl())
        val doc = gradePage.document()
        if (AcademicParser.isLogin(doc)) fail(AcademicProblem.LOGIN)
        val identity = AcademicParser.identity(doc, account)
        currentCoroutineContext().ensureActive()
        if (closed.get()) throw CancellationException("Academic session closed")
        verified = identity; cachedGrades = gradePage
        identity
    }

    override suspend fun exams(): List<SchoolExam> = inSession {
        requireIdentity()
        var response = follow((ACADEMIC_ORIGIN + EXAMS_PATH).toHttpUrl())
        var doc = response.document()
        if (AcademicParser.isLogin(doc)) fail(AcademicProblem.EXPIRED)
        val (allTermsValue, allTermsSelected) = examTermFilter(doc)
        if (!allTermsSelected) {
            val fields = pageFields(doc)
            fields["ctl00\$MainWork\$txtxq"] = allTermsValue
            fields["ctl00\$MainWork\$btnSearch.x"] = "1"
            fields["ctl00\$MainWork\$btnSearch.y"] = "1"
            response = pagePost(response, fields)
            doc = response.document()
        }
        val output = mutableListOf<SchoolExam>()
        val seenPages = mutableSetOf<String>()
        repeat(100) {
            // A successful POST or pager response can still return the previous term.
            // Do not replace the complete saved snapshot with that partial result.
            if (!examTermFilter(doc).second) fail()
            val parsed = AcademicParser.exams(doc)
            output += parsed.records
            if (output.size > 5000) fail()
            val next = parsed.next ?: return@inSession output
            val marker = pageMarker(doc, "MainWork_dgData")
            if (!seenPages.add(marker)) fail()
            response = postBack(response, next); doc = response.document()
        }
        fail()
    }

    private fun examTermFilter(doc: Document): Pair<String, Boolean> {
        if (AcademicParser.isLogin(doc)) fail(AcademicProblem.EXPIRED)
        val select = doc.getElementById("MainWork_txtxq") ?: fail()
        if (select.tagName() != "select" || select.attr("name") != "ctl00\$MainWork\$txtxq") fail()
        val options = select.select("option")
        val all = options.singleOrNull { AcademicParser.text(it).replace("-", "") == "全部" } ?: fail()
        val selected = options.filter { it.hasAttr("selected") }
        if (selected.size > 1) fail()
        return all.attr("value") to ((selected.singleOrNull() ?: options.firstOrNull()) === all)
    }

    override suspend fun grades(now: Long): GradeSnapshot = inSession {
        val identity = requireIdentity()
        val initial = cachedGrades ?: follow((ACADEMIC_ORIGIN + GRADES_PATH).toHttpUrl())
        cachedGrades = null
        fun check(doc: Document) {
            if (AcademicParser.isLogin(doc)) fail(AcademicProblem.EXPIRED)
            AcademicParser.identity(doc, identity.studentId)
        }
        check(initial.document())
        val credits = AcademicParser.credits(initial.document())
        var paginated = false
        suspend fun readGrid(id: String, first: AcademicResponse): List<GradeRecord> {
            var response = first
            val result = mutableListOf<GradeRecord>()
            val seenPages = mutableSetOf<String>()
            repeat(100) {
                val doc = response.document(); check(doc)
                val parsed = AcademicParser.grades(doc, id)
                result += parsed.records
                if (result.size > 5000) fail()
                val next = parsed.next ?: return result
                if (!seenPages.add(pageMarker(doc, id))) fail()
                paginated = true
                response = postBack(response, next)
            }
            fail()
        }
        val degree = readGrid("MainWork_dgData", initial)
        // A new GET initializes the second grid after paging, rather than reusing stale form state.
        val electiveStart = if (paginated) follow((ACADEMIC_ORIGIN + GRADES_PATH).toHttpUrl()) else initial
        val elective = readGrid("MainWork_Datagrid1", electiveStart)
        GradeSnapshot(degree, elective, credits, now)
    }

    private fun pageMarker(doc: Document, grid: String): String = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest((doc.getElementById(grid)?.outerHtml() ?: fail()).toByteArray(Charsets.UTF_8)))

    private fun requireIdentity(): AcademicIdentity = verified ?: fail(AcademicProblem.EXPIRED)

    private suspend fun loginPage(response: AcademicResponse): AcademicLoginPage {
        if (response.url.encodedPath != LOGIN_PATH) fail(AcademicProblem.LOGIN)
        val doc = response.document()
        val element = doc.selectFirst("form#Form1") ?: fail()
        val action = response.url.resolve(element.attr("action").ifBlank { response.url.toString() }) ?: fail(AcademicProblem.ADDRESS)
        if (!allowedAcademicUrl(action) || action.encodedPath != LOGIN_PATH || !element.attr("method").equals("post", true)) fail(AcademicProblem.ADDRESS)
        val expectedControls = mapOf("ctl00_txtusername" to USERNAME, "ctl00_txtpassword" to PASSWORD,
            "ctl00_txtyzm" to CAPTCHA, "ctl00_ImageButton1" to SUBMIT, "ctl00_pubkey" to PUBLIC_KEY)
        expectedControls.forEach { (id, name) ->
            val control = element.getElementById(id) ?: fail()
            if (control.attr("name") != name || element.select("input[name]").count { it.attr("name") == name } != 1) fail()
        }
        if (element.getElementById("ctl00_txtpassword")?.attr("type") != "password") fail()
        val hidden = hiddenFields(doc)
        val key = element.getElementById("ctl00_pubkey")?.attr("value")?.takeIf { it.length in 64..8192 } ?: fail()
        hidden[PUBLIC_KEY] = key
        val image = element.getElementById("ctl00_myCode") ?: fail()
        val source = image.attr("src")
        val challenge = if (source.startsWith("data:image/")) {
            if (source.length > 256 * 1024 || !Regex("""data:image/(?:png|jpeg|gif|webp);base64,[A-Za-z0-9+/=\r\n]+""").matches(source)) fail()
            try { Base64.getMimeDecoder().decode(source.substringAfter(',')) } catch (_: IllegalArgumentException) { fail() }
        } else {
            val url = response.url.resolve(source) ?: fail(AcademicProblem.ADDRESS)
            if (!allowedAcademicUrl(url) || !url.encodedPath.equals(CAPTCHA_PATH, ignoreCase = true)) fail(AcademicProblem.ADDRESS)
            follow(url).bytes
        }
        if (challenge.isEmpty() || challenge.size > 192 * 1024) fail()
        val token = UUID.randomUUID().toString()
        synchronized(formLock) { if (closed.get()) throw CancellationException("Academic session closed"); pageToken = token; form = AcademicForm(action, hidden, key) }
        return AcademicLoginPage(token, challenge)
    }

    private fun hiddenFields(doc: Document): MutableMap<String, String> {
        val result = linkedMapOf<String, String>()
        doc.select("form input[type=hidden][name]").forEach { element ->
            val name = element.attr("name")
            if (name.matches(Regex("""__VIEWSTATE\d*|__VIEWSTATEGENERATOR|__VIEWSTATEFIELDCOUNT|__EVENTVALIDATION|__EVENTTARGET|__EVENTARGUMENT"""))) {
                if (result.put(name, element.attr("value")) != null) fail()
            }
        }
        if (result["__VIEWSTATE"].isNullOrBlank() || result.size > 64 || result.values.sumOf { it.length } > 512 * 1024) fail()
        return result
    }

    private fun pageFields(doc: Document): MutableMap<String, String> = hiddenFields(doc).also { fields ->
        doc.select("form select[name]").forEach { select ->
            if (select.attr("name") != "ctl00\$MainWork\$txtxq") fail()
            val value = select.selectFirst("option[selected]") ?: select.selectFirst("option") ?: fail()
            fields[select.attr("name")] = value.attr("value")
        }
    }

    private suspend fun postBack(response: AcademicResponse, next: AcademicPostBack): AcademicResponse {
        val fields = pageFields(response.document())
        fields["__EVENTTARGET"] = next.target; fields["__EVENTARGUMENT"] = next.argument
        return pagePost(response, fields)
    }

    private suspend fun pagePost(response: AcademicResponse, fields: Map<String, String>): AcademicResponse {
        val doc = response.document()
        val element = doc.selectFirst("form") ?: fail()
        val action = response.url.resolve(element.attr("action").ifBlank { response.url.toString() }) ?: fail(AcademicProblem.ADDRESS)
        if (action.encodedPath != response.url.encodedPath || !element.attr("method").equals("post", true)) fail(AcademicProblem.ADDRESS)
        val body = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build().oneShot()
        val result = follow(action, body, response.url)
        if (result.url.encodedPath != action.encodedPath || AcademicParser.isLogin(result.document())) fail(AcademicProblem.EXPIRED)
        return result
    }

    private suspend fun follow(initial: HttpUrl, body: RequestBody? = null, referer: HttpUrl? = null): AcademicResponse {
        var target = initial
        var post = body
        repeat(8) {
            currentCoroutineContext().ensureActive()
            if (closed.get()) throw CancellationException("Academic session closed")
            if (!allowedAcademicUrl(target)) fail(AcademicProblem.ADDRESS)
            val response = request(target, post, referer)
            if (response.redirect != null) {
                if (post != null && response.code in setOf(307, 308)) fail(AcademicProblem.ADDRESS)
                var redirected = target.resolve(response.redirect) ?: fail(AcademicProblem.ADDRESS)
                // Normalize only known same-host read routes emitted by the school's reverse proxy.
                // The original POST is never replayed, and no request is sent over HTTP.
                if (redirected.scheme == "http" && redirected.host == target.host && redirected.port == 80 &&
                    redirected.username.isEmpty() && redirected.password.isEmpty() &&
                    redirected.encodedPath in setOf(LOGIN_PATH, "/pyxx/default.aspx", "/pyxx/loging.aspx", EXAMS_PATH, GRADES_PATH)) {
                    redirected = redirected.newBuilder().scheme("https").port(443).build()
                }
                target = redirected; post = null
            } else {
                if (response.code !in 200..299) fail(AcademicProblem.CONNECTION)
                return AcademicResponse(target, response.bytes, response.charset)
            }
        }
        fail(AcademicProblem.ADDRESS)
    }

    private class RawResponse(val code: Int, val bytes: ByteArray, val charset: Charset?, val redirect: String?)
    private suspend fun request(url: HttpUrl, body: RequestBody?, referer: HttpUrl?): RawResponse = withTimeoutOrNull(20_000) {
        val addresses = try { dns.lookup(url.host) } catch (error: CancellationException) { throw error }
        catch (_: Exception) { fail(AcademicProblem.CONNECTION) }
        if (addresses.isEmpty()) fail(AcademicProblem.CONNECTION)
        currentCoroutineContext().ensureActive()
        if (closed.get()) throw CancellationException("Academic session closed")
        val resolved = client.newBuilder().dns(object : Dns {
            override fun lookup(hostname: String) = if (hostname == url.host) addresses
                else throw java.net.UnknownHostException("Unexpected academic host")
        }).build()
        val builder = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
            .header("Cache-Control", "no-store").header("Accept-Language", "zh-CN,zh;q=0.9")
        if (referer != null) builder.header("Referer", referer.toString())
        if (body != null) builder.post(body)
        val call = resolved.newCall(builder.build())
        val finished = CompletableDeferred<Unit>()
        calls += call
        if (closed.get()) call.cancel()
        try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                try {
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            calls.remove(call); finished.complete(Unit)
                            if (continuation.isActive) continuation.resumeWithException(AcademicException(AcademicProblem.CONNECTION))
                        }
                        override fun onResponse(call: Call, response: Response) {
                            try {
                                val result = response.use {
                                    val redirect = if (it.code in setOf(301, 302, 303, 307, 308)) it.header("Location") else null
                                    RawResponse(it.code, if (redirect != null) ByteArray(0) else it.body?.byteStream()?.use { stream -> stream.readBounded(2 * 1024 * 1024) }
                                        ?: fail(), it.body?.contentType()?.charset(), redirect)
                                }
                                if (continuation.isActive) continuation.resume(result)
                            } catch (_: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(AcademicException(AcademicProblem.RESPONSE))
                            } finally { calls.remove(call); finished.complete(Unit) }
                        }
                    })
                } catch (_: Exception) {
                    calls.remove(call); finished.complete(Unit)
                    if (continuation.isActive) continuation.resumeWithException(AcademicException(AcademicProblem.CONNECTION))
                }
            }
        } finally { withContext(NonCancellable) { finished.await() } }
    } ?: fail(AcademicProblem.CONNECTION)

    private suspend fun <T> inSession(action: suspend () -> T): T = coroutineScope {
        val operation = currentCoroutineContext()[Job]!!
        synchronized(operations) { if (closed.get()) throw CancellationException("Academic session closed"); operations += operation }
        try { action() } finally { synchronized(operations) { operations -= operation } }
    }

    override fun close() {
        val pending = synchronized(operations) { closed.set(true); operations.toList() }
        synchronized(formLock) { pageToken = null; form = null }
        verified = null; cachedGrades = null; cookies.clear()
        pending.forEach { it.cancel() }; calls.forEach { it.cancel() }
    }
    override suspend fun awaitClosed() { synchronized(operations) { check(closed.get()); operations.toList() }.joinAll() }
    private fun fail(problem: AcademicProblem = AcademicProblem.RESPONSE): Nothing = throw AcademicException(problem)
}

private fun RequestBody.oneShot(): RequestBody {
    val original = this
    return object : RequestBody() {
        override fun contentType() = original.contentType()
        override fun contentLength() = original.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: okio.BufferedSink) = original.writeTo(sink)
    }
}

/** JSEncrypt's RSAES-PKCS1-v1_5 transformation, using the current login page's public key. */
internal fun encryptAcademicPassword(publicKey: String, password: String): String {
    try {
        val pkcs1 = publicKey.contains("BEGIN RSA PUBLIC KEY")
        val payload = publicKey.replace(Regex("""-----[^-]+-----|\s"""), "")
        val der = Base64.getDecoder().decode(payload)
        fun encoded(tag: Int, data: ByteArray): ByteArray {
            val length = when {
                data.size < 128 -> byteArrayOf(data.size.toByte())
                data.size < 256 -> byteArrayOf(0x81.toByte(), data.size.toByte())
                else -> byteArrayOf(0x82.toByte(), (data.size shr 8).toByte(), data.size.toByte())
            }
            return byteArrayOf(tag.toByte()) + length + data
        }
        val wrappedPkcs1 = encoded(0x30,
            byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00) +
                encoded(0x03, byteArrayOf(0) + der))
        val factory = KeyFactory.getInstance("RSA")
        val key = (if (pkcs1) factory.generatePublic(X509EncodedKeySpec(wrappedPkcs1)) else
            runCatching { factory.generatePublic(X509EncodedKeySpec(der)) }.getOrElse { factory.generatePublic(X509EncodedKeySpec(wrappedPkcs1)) }) as RSAPublicKey
        // The school's JSEncrypt build encodes each UTF-16 code unit, including surrogate pairs.
        val bytes = java.io.ByteArrayOutputStream(password.length * 3).apply {
            password.forEach { character ->
                val code = character.code
                when {
                    code < 128 -> write(code)
                    code < 2048 -> { write((code shr 6) or 192); write((code and 63) or 128) }
                    else -> { write((code shr 12) or 224); write(((code shr 6) and 63) or 128); write((code and 63) or 128) }
                }
            }
        }.toByteArray()
        if (key.modulus.bitLength() !in 1024..8192 || bytes.size > (key.modulus.bitLength() + 7) / 8 - 11)
            throw AcademicException(AcademicProblem.LOGIN)
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, key)
        return Base64.getEncoder().encodeToString(rsa.doFinal(bytes))
    } catch (error: AcademicException) { throw error }
    catch (_: Exception) { throw AcademicException(AcademicProblem.RESPONSE) }
}
