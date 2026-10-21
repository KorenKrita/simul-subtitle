package io.github.korenkrita.simulsub

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class JevTest {
    // A one-request-at-a-time HTTP/1.1 server (com.sun.net.httpserver is not on the Android unit-test classpath).
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val port = server.localPort
    private val hits = AtomicInteger()
    private val lastAuth = AtomicReference<String?>()
    private val lastBody = AtomicReference<String?>()
    @Volatile private var reply: (Int) -> Pair<Int, String> = { 200 to "{}" }
    @Volatile private var delayMs = 0L
    @Volatile private var trickleMs = 0L
    @Volatile private var redirectTo: String? = null
    /** extra header lines for the plain reply, e.g. "Retry-After: 30" */
    @Volatile private var extraHeaders = ""
    /** the headers of the last request, names lowercased */
    private val lastHeaders = AtomicReference<Map<String, String>>(emptyMap())
    /** the path of the last request */
    private val lastPath = AtomicReference<String?>()
    /** when set, the reply to request number [holdHit] waits on [holdGate] after it is received (truly in flight) */
    @Volatile private var holdHit = -1
    private val holdGate = java.util.concurrent.CountDownLatch(1)
    private val held = java.util.concurrent.CountDownLatch(1)
    /** streamed reply: (text, pause before it) pieces written one by one after the headers, no Content-Length */
    @Volatile private var pieces: List<Pair<String, Long>>? = null

    init {
        Thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    s.use { sock ->
                        val inp = sock.getInputStream()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) return@use; head.append(b.toChar()) }
                        val headers = head.lines().drop(1).filter { ':' in it }
                            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                        val len = headers["content-length"]?.toInt() ?: 0
                        val body = ByteArray(len).also { var o = 0; while (o < len) o += inp.read(it, o, len - o) }
                        hits.incrementAndGet()
                        lastAuth.set(headers["authorization"]); lastHeaders.set(headers); lastBody.set(String(body))
                        lastPath.set(head.lineSequence().first().split(' ').getOrNull(1))
                        val n = hits.get()
                        if (n == holdHit) { held.countDown(); holdGate.await() }
                        Thread.sleep(delayMs)
                        val out = sock.getOutputStream()
                        val r = redirectTo
                        if (r != null) { out.write("HTTP/1.1 307 Temporary Redirect\r\nLocation: $r\r\nContent-Length: 0\r\n\r\n".toByteArray()); return@use }
                        pieces?.let { ps ->
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
                            for ((p, pause) in ps) { Thread.sleep(pause); out.write(p.toByteArray()); out.flush() }
                            return@use
                        }
                        val (code, text) = reply(n)
                        val b = text.toByteArray()
                        out.write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\n${extraHeaders}Connection: close\r\n\r\n".toByteArray())
                        out.flush()
                        // trickle: one byte every trickleMs, each gap shorter than any read timeout
                        if (trickleMs > 0) for (x in b) { out.write(x.toInt()); out.flush(); Thread.sleep(trickleMs) }
                        else { out.write(b); out.flush() }
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
    }

    @After fun stop() = server.close()

    private fun jev(deadline: Long = 2_000) =
        Jev(JevProvider.OFFICIAL, "sk-secret", deadline, url = "http://127.0.0.1:$port/v1/systemone")

    private fun online(deadline: Long = 2_000) = ByokTranslator(ByokService("http://127.0.0.1:$port/v1/chat/completions", "m-1", "sk-test"), "SYS", null, deadlineMs = deadline)
    private fun sse(vararg chunks: String) = chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    @Test
    fun aStreamedTranslationIsAssembledWithItsUsage() {
        // stream shape recorded from an OpenAI-compatible service (role-only first chunk, usage at the end)
        reply = { 200 to sse("""{"choices":[{"finish_reason":null,"index":0,"delta":{"role":"assistant","content":null}}]}""",
            """{"choices":[{"finish_reason":null,"index":0,"delta":{"content":"真的"}}]}""",
            """{"choices":[{"finish_reason":null,"index":0,"delta":{"content":"吗？"}}]}""",
            """{"choices":[{"finish_reason":"stop","index":0,"delta":{}}]}""",
            """{"choices":[],"usage":{"prompt_tokens":34,"completion_tokens":4}}""") }
        val r = online().translate(byokUser("本当に？"))!!
        assertEquals("真的吗？", r.text); assertTrue(r.usable); assertEquals(200, r.http); assertNull(r.error)
        assertEquals(34, r.promptTokens); assertEquals(4, r.outputTokens); assertNotNull(r.firstContentMs)
        val req = Json.parse(lastBody.get()!!) as Map<*, *>
        assertEquals("m-1", req["model"]); assertEquals(true, req["stream"])
        assertEquals("【当前句】\n本当に？", ((req["messages"] as List<*>)[1] as Map<*, *>)["content"])
        assertEquals(r.request, lastBody.get())
    }

    @Test
    fun failuresAreUnusableAndLogged() {
        reply = { 503 to "busy" }
        val a = online().translate("x")!!
        assertFalse(a.usable); assertEquals(503, a.http); assertEquals("HTTP 503", a.error); assertNull(a.response)
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"很长"},"finish_reason":"length"}]}""") }
        val b = online().translate("x")!!
        assertEquals('l', b.stop); assertTrue(b.usable); assertNull(b.promptTokens)
        delayMs = 3_000
        val c = online(deadline = 300).translate("x")!!
        assertFalse(c.usable); assertTrue(c.error!!, c.error!!.startsWith("deadline"))
    }

    @Test
    fun theServiceHeadersGoOutWithEachRequest() {
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}""") }
        val url = "http://127.0.0.1:$port/v1/chat/completions"
        // defaults: our User-Agent and the key
        ByokTranslator(ByokService(url, "m-1", "sk-test"), "SYS", null).translate("x")!!
        assertEquals(ByokTranslator.defaultUserAgent, lastHeaders.get()["user-agent"]); assertEquals("Bearer sk-test", lastAuth.get())
        // a typed User-Agent and extra headers, no key: no Authorization at all
        val free = ByokService(url, "m-1", "", "Index-Translate-Client/1.0", listOf("X-Team" to "a b", "X-Two" to "2"))
        assertTrue(ByokTranslator(free, "SYS", null).translate("x")!!.usable)
        val h = lastHeaders.get()
        assertEquals("Index-Translate-Client/1.0", h["user-agent"]); assertEquals("a b", h["x-team"]); assertEquals("2", h["x-two"])
        assertNull(lastAuth.get())
        // the log has the User-Agent and the header names, never their values
        assertEquals(mapOf("mt_user_agent" to "Index-Translate-Client/1.0", "mt_headers" to listOf("X-Team", "X-Two")), free.log())
    }

    @Test
    fun onlyHeadersThatCanBeSentAreAccepted() {
        assertNull(ByokService.badName("X-Api-Key")); assertNull(ByokService.badValue("X", "Bearer a.b-c_d=="))
        for (n in listOf("", "X Key", "X:Y", "头", "Authorization", "content-type", "Host", "User-Agent")) assertNotNull(n, ByokService.badName(n))
        assertNotNull(ByokService.badValue("X", "a\r\nX-Evil: 1")); assertNotNull(ByokService.badValue("X", "中文"))
        assertNotNull(ByokService.badUserAgent("UA\n")); assertNull(ByokService.badUserAgent("Mozilla/5.0 (X; Y)"))
        // stored and read back; a bad or broken entry is dropped on reading, not sent
        val h = listOf("X-A" to "1", "X-B" to "")
        assertEquals(h, ByokService.parseHeaders(ByokService.storeHeaders(h)))
        assertEquals(listOf("X-A" to "1"), ByokService.parseHeaders("""[["X-A","1"],["Host","evil"],["X-C"],5]"""))
        assertEquals(emptyList<Pair<String, String>>(), ByokService.parseHeaders("not json"))
        assertEquals(emptyList<Pair<String, String>>(), ByokService.parseHeaders(null))
        // the 高级设置 as a whole: a repeated name (any case) and more than the cap are refused too
        assertNull(ByokService.badAdvanced("", listOf("X-A" to "1")))
        assertNotNull(ByokService.badAdvanced("", listOf("X-A" to "1", "x-a" to "2")))
        assertNotNull(ByokService.badAdvanced("", (0..ByokService.MAX_HEADERS).map { "X-$it" to "v" }))
        assertNotNull(ByokService.badAdvanced("", listOf("Host" to "x")))
    }

    private fun streamed(firstContentMs: Long, deadline: Long = 8_000) =
        ByokTranslator(ByokService("http://127.0.0.1:$port/v1/chat/completions", "m-1", "sk-test"), "SYS", null, deadlineMs = deadline, firstContentMs = firstContentMs)

    @Test
    fun noTranslatedTextWithinTheFirstContentWaitFailsTheCaption() {
        // a queueing server: nothing at all
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"晚"},"finish_reason":"stop"}]}""") }
        delayMs = 3_000
        val t0 = monoMs(); val r = streamed(300).translate("x")!!
        assertFalse(r.usable); assertEquals("no content in 300ms", r.error); assertTrue(monoMs() - t0 < 2_000)
        // headers, a role-only chunk and a keep-alive at once, the text only later: still no content in time
        delayMs = 0
        pieces = listOf("data: " + """{"choices":[{"delta":{"role":"assistant","content":null}}]}""" + "\n\n" to 0L,
            ": keep-alive\n\n" to 50L,
            "data: " + """{"choices":[{"delta":{"content":"晚上好"},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n" to 1_000L)
        val late = streamed(300).translate("x")!!
        assertFalse(late.usable); assertEquals("no content in 300ms", late.error)
    }

    @Test
    fun onceTextFlowsTheRestIsWaitedForUpToTheDeadline() {
        val chunk = { s: String -> "data: " + """{"choices":[{"delta":{"content":"$s"}}]}""" + "\n\n" }
        pieces = listOf(chunk("晚") to 50L, chunk("上") to 600L, chunk("好") to 600L,
            "data: " + """{"choices":[{"delta":{},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n" to 0L)
        val r = streamed(300).translate("x")!!
        assertTrue(r.error, r.usable); assertEquals("晚上好", r.text); assertTrue(r.firstContentMs!! < 300)
        // but the whole call still ends at the deadline, counted from sending
        pieces = listOf(chunk("晚") to 50L, chunk("上") to 2_000L)
        val slow = streamed(300, deadline = 1_000).translate("x")!!
        assertFalse(slow.usable); assertTrue(slow.error!!, slow.error!!.startsWith("deadline")); assertNotNull(slow.firstContentMs)
    }

    @Test
    fun stoppingDuringTheFirstContentWaitReturnsAtOnce() {
        delayMs = 3_000
        val u = streamed(2_000)
        val got = AtomicReference<MtResult?>()
        val th = Thread { got.set(u.translate("x")) }.apply { start() }
        Thread.sleep(300); val c0 = monoMs(); u.cancel(); th.join(1_000)
        assertEquals("cancelled", got.get()!!.error); assertTrue(monoMs() - c0 < 100)
    }

    @Test
    fun aStoppedTranslatorSendsNothingMore() {
        val t = online(); t.cancel()
        val r = t.translate("x")!!
        assertFalse(r.usable); assertEquals("cancelled", r.error)
        Thread.sleep(200); assertEquals(0, hits.get())
    }

    @Test
    fun aCancelledRequestIsStillLoggedWithWhatWasSent() {
        delayMs = 3_000
        val t = online(deadline = 5_000)
        val r = AtomicReference<MtResult?>()
        val th = Thread { r.set(t.translate("请翻译\n\n本当に？")) }.apply { start() }
        val until = monoMs() + 2_000
        while (hits.get() < 1 && monoMs() < until) Thread.sleep(10)
        t.cancel(); th.join(2_000)
        val res = r.get()!!
        assertFalse(res.usable); assertEquals("cancelled", res.error)
        assertTrue(res.request!!.contains("本当に"))
    }

    /** lookups of "stuck.invalid" hang until [release]; afterwards they resolve to the local test server */
    private inner class HangingDns : okhttp3.Dns {
        val release = java.util.concurrent.CountDownLatch(1)
        @Volatile var entered = java.util.concurrent.CountDownLatch(1)
        override fun lookup(hostname: String): List<InetAddress> {
            entered.countDown(); release.await()
            return listOf(InetAddress.getByName("127.0.0.1"))
        }
    }
    private fun stuckUrl(path: String) = "http://stuck.invalid:$port/$path"

    @Test(timeout = 20_000)
    fun aHangingDnsLookupStillEndsAtTheDeadlineAndOnStop() {
        val dns = HangingDns()
        try {
            val t0 = monoMs()
            val a = Calls(300, dns, java.net.Proxy.NO_PROXY).post(stuckUrl("x"), "{}", emptyMap())
            assertTrue("deadline: took ${monoMs() - t0} ms", monoMs() - t0 < 400)
            assertEquals("the lookup is what hangs", 0L, dns.entered.count)
            assertTrue("${a.error}", a.error?.startsWith("deadline") == true)

            dns.entered = java.util.concurrent.CountDownLatch(1)
            val c = Calls(5_000, dns, java.net.Proxy.NO_PROXY)
            val r = AtomicReference<HttpResult?>()
            val th = Thread { r.set(c.post(stuckUrl("y"), "{}", emptyMap())) }.apply { start() }
            assertTrue("second request reached the lookup", dns.entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val t1 = monoMs(); c.cancel(); th.join(1_000)
            assertFalse(th.isAlive); assertTrue("stop: took ${monoMs() - t1} ms", monoMs() - t1 < 500)
            assertEquals("cancelled", r.get()?.error)
        } finally { dns.release.countDown() }
        // both calls were cancelled while resolving: once the lookup returns they must not be sent
        Thread.sleep(500); assertEquals(0, hits.get())
    }

    @Test(timeout = 30_000)
    fun stuckWorkIsBoundedAcrossSessionsAndExtraRequestsFailAtOnce() {
        val dns = HangingDns()
        val results = mutableListOf<HttpResult>()
        try {
            repeat(Calls.MAX_PENDING + 4) { i ->
                // a fresh "session" each time, as after stop and start
                results += Calls(100, dns, java.net.Proxy.NO_PROXY).post(stuckUrl("$i"), "{}", emptyMap())
                assertTrue("pending ${Calls.pendingNow()}", Calls.pendingNow() <= Calls.MAX_PENDING)
            }
            assertEquals(Calls.MAX_PENDING, Calls.pendingNow())
            val t0 = monoMs()
            val busy = Calls(5_000, dns, java.net.Proxy.NO_PROXY).post(stuckUrl("z"), "{}", emptyMap())
            assertEquals("busy", busy.error); assertTrue(monoMs() - t0 < 200)
            assertEquals(4, results.count { it.error == "busy" })
        } finally { dns.release.countDown() }
        // slots come back when OkHttp has really finished the calls, and none of them is sent
        val until = monoMs() + 5_000
        while (Calls.pendingNow() > 0 && monoMs() < until) Thread.sleep(20)
        assertEquals(0, Calls.pendingNow())
        Thread.sleep(300); assertEquals(0, hits.get())
    }

    @Test
    fun anUnknownFinishReasonIsNotAUsableTranslation() {
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"半分"},"finish_reason":"content_filter"}]}""") }
        val a = online().translate("x")!!
        assertFalse(a.usable); assertEquals("finish_reason", a.error)
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"半分"}}]}""") }
        assertFalse(online().translate("x")!!.usable)
        reply = { 200 to """{"choices":[{"message":{"content":"不是流"},"finish_reason":"stop"}]}""" }
        assertEquals("bad response", online().translate("x")!!.error)
        // a broken chunk in the middle could have held part of the text
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"我"}}]}""", """{"choices":[{"delta":{"content":"不"}}]""",
            """{"choices":[{"delta":{"content":"同意"},"finish_reason":"stop"}]}""") }
        assertEquals("bad response", online().translate("x")!!.error)
        // an error event between good chunks, a stream cut off before [DONE], a wrongly typed content
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"我"}}]}""", """{"error":{"message":"overloaded"}}""",
            """{"choices":[{"delta":{"content":"同意"},"finish_reason":"stop"}]}""") }
        assertEquals("bad response", online().translate("x")!!.error)
        reply = { 200 to "data: " + """{"choices":[{"delta":{"content":"我"},"finish_reason":"stop"}]}""" + "\n\n" }
        assertEquals("bad response", online().translate("x")!!.error)
        reply = { 200 to sse("""{"choices":[{"delta":{"content":5},"finish_reason":"stop"}]}""") }
        assertEquals("bad response", online().translate("x")!!.error)
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"我"}}]}""", """{"choices":[null]}""",
            """{"choices":[{"delta":{"content":"同意"},"finish_reason":"stop"}]}""") }
        assertEquals("bad response", online().translate("x")!!.error)
        reply = { 200 to "event: error\n" + sse("""{"choices":[]}""", """{"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}""") }
        assertEquals("bad response", online().translate("x")!!.error)
    }

    @Test
    fun redactHandlesBodiesThatAreNotValidJsonAndKeysInNumbers() {
        // review 06a6be2: trailing text after JSON, and a numeric key echoed as a JSON number
        val a = Jev.redact("""{"error":"invalid key \u0073k-secret"} upstream error""", "sk-secret")
        assertFalse(a, a.contains("sk-secret") || a.contains("\\u0073k-secret")); assertTrue(a.contains("***"))
        val b = Jev.redact("""{"error":"invalid key","key":1234567890}""", "1234567890")
        assertEquals("""{"error":"invalid key","key":***}""", b)
        val c = Jev.redact("""{"e":"\u0053\u004B-SECRET \u0073\u006b\u002dsecret"}""", "sk-secret")
        assertFalse(c, c.contains("\\u0073\\u006b\\u002dsecret")); assertTrue(c, c.contains("SECRET")) // other case untouched
    }

    @Test
    fun redactKeepsCleanResponsesByteForByteAndCatchesEveryEscapedKey() {
        val raw = """{ "answers" : {"x":{"noul":0.25}},  "unicode":"\u672c" }"""
        assertEquals(raw, Jev.redact(raw, "sk-secret"))
        assertEquals("not json", Jev.redact("not json", "sk-secret"))
        val k = "sk-a\"b\\c"
        for (body in listOf(
            "\"invalid key \\u0073k-a\\\"b\\\\c\"", // a top-level JSON string
            """{"e":["x \u0073k-a\"b\\c"]}""",
            """{"\u0073k-a\"b\\c":1}""",
        )) {
            val out = Jev.redact(body, k)
            assertFalse(out, out.contains(k) || (runCatching { Json.parse(out) }.getOrNull()?.toString() ?: "").contains(k))
            assertTrue(out, out.contains("***"))
        }
    }

    @Test
    fun aSlowlyTricklingResponseIsStillCutAtTheDeadline() {
        // every byte arrives well within the read timeout, so only a whole-call deadline can stop this
        reply = { 200 to "{\"answers\":{\"x\":{\"noul\":0.5}}," + " ".repeat(200) + "\"pad\":1}" }
        trickleMs = 50
        val t0 = monoMs()
        val c = jev(deadline = 600).unfinished("はい")
        assertTrue("took ${monoMs() - t0} ms", monoMs() - t0 < 1_500)
        assertNull(c.answer); assertTrue(c.error!!, c.error!!.startsWith("deadline"))
    }

    @Test
    fun cancelAbortsBothKindsOfRequestInFlightAtOnce() {
        delayMs = 3_000
        val j = jev(deadline = 5_000)
        val a = AtomicReference<JevCall?>(); val b = AtomicReference<JevCall?>()
        val ta = Thread { a.set(j.unfinished("はい")) }.apply { start() }
        val tb = Thread { b.set(j.mistranslation("はい", "是")) }.apply { start() }
        val until = monoMs() + 2_000
        while (hits.get() < 2 && monoMs() < until) Thread.sleep(10)
        assertEquals(2, hits.get())
        val t0 = monoMs(); j.cancel(); ta.join(2_000); tb.join(2_000)
        assertFalse(ta.isAlive || tb.isAlive)
        assertTrue("took ${monoMs() - t0} ms", monoMs() - t0 < 1_500)
        assertEquals("cancelled", a.get()!!.error); assertEquals("cancelled", b.get()!!.error)
    }

    @Test
    fun zenIsCalledWithoutAnyKeyAndParsesTheSameShape() {
        // recorded from opencode.ai/zen/v1/systemone on 2026-10-10 (no Authorization header; a wrong key gets 401)
        reply = { 200 to """{"model":"jev-1.13-free","answers":{"x":{"type":"noul","noul":0.96}},"usage":{"input_tokens":320,"output_tokens":20}}""" }
        val c = Jev(JevProvider.ZEN, "", url = "http://127.0.0.1:$port/zen/v1/systemone").unfinished("スパチャありがとうございま")
        assertEquals(0.96, c.noul!!, 1e-9); assertNull(c.error)
        assertNull("no Authorization header for Zen", lastAuth.get())
        assertEquals("jev-1.13-free", (Json.parse(lastBody.get()!!) as Map<*, *>)["model"])
        assertEquals(JevProvider.ZEN, JevProvider.entries.first())
    }

    @Test
    fun aCustomServiceGetsTheProtocolPathUnlessItsAddressNamesOne() {
        fun url(p: JevProtocol, u: String) = JevCustom(p, u, "m").endpoint().second
        assertEquals("https://api.typesafe.ai/v1/systemone", url(JevProtocol.SYSTEMONE, "https://api.typesafe.ai/"))
        assertEquals("https://openrouter.ai/api/alpha/decisions", url(JevProtocol.DECISIONS, "https://openrouter.ai/api"))
        // CLIProxyAPI: systemone under the management path; decisions at …/decisions, not …/alpha/decisions
        assertEquals("http://127.0.0.1:8317/v0/management/v1/systemone", url(JevProtocol.SYSTEMONE, "http://127.0.0.1:8317/v0/management"))
        assertEquals("http://127.0.0.1:8317/v0/management/decisions", url(JevProtocol.DECISIONS, "http://127.0.0.1:8317/v0/management/decisions"))
        // a full address is kept whichever protocol is chosen
        assertEquals("https://x.dev/api/alpha/decisions", url(JevProtocol.SYSTEMONE, "https://x.dev/api/alpha/decisions/"))
        assertEquals("地址要以 http:// 或 https:// 开头", JevCustom(JevProtocol.SYSTEMONE, "ftp://x.dev", "m").endpoint().first)
        assertEquals("还没填地址", JevCustom(JevProtocol.SYSTEMONE, " ", "m").endpoint().first)
        // saved as one value; anything unreadable is "not saved"
        val back = JevCustom.decode(JevCustom(JevProtocol.DECISIONS, "http://h/x", "jev-tencent").encode())!!
        assertEquals(listOf(JevProtocol.DECISIONS, "http://h/x", "jev-tencent"), listOf(back.protocol, back.url, back.model))
        assertNull(JevCustom.decode("""{"protocol":"SOAP","url":"u","model":"m"}"""))
        assertNull(JevCustom.decode(null))
    }

    @Test
    fun aCustomServiceSendsItsModelAndAKeyOnlyWhenOneIsSaved() {
        reply = { 200 to """{"answers":{"x":{"type":"noul","noul":0.9}}}""" }
        val u = "http://127.0.0.1:$port/v0/management/v1/systemone"
        assertEquals(0.9, Jev(JevProvider.CUSTOM, "", url = u, model = "jev-tencent").unfinished("はい").noul!!, 1e-9)
        assertNull("no key: no Authorization header", lastAuth.get())
        assertEquals("jev-tencent", (Json.parse(lastBody.get()!!) as Map<*, *>)["model"])
        assertEquals("/v0/management/v1/systemone", lastPath.get())
        Jev(JevProvider.CUSTOM, "mgmt-key", url = u, model = "jev-tencent").unfinished("はい")
        assertEquals("Bearer mgmt-key", lastAuth.get())
    }

    @Test
    fun aDecisionsChoiceWithoutConfidenceUsesItsProbability() {
        // OpenRouter Decisions API: confidence is optional, probabilities name every class
        reply = { 200 to """{"answers":{"x":{"type":"choice","choice":"reversal","probabilities":{"ok":0.1,"reversal":0.85}}}}""" }
        val c = Jev(JevProvider.CUSTOM, "", url = "http://127.0.0.1:$port/api/alpha/decisions", model = "typesafe/jev-1.14").mistranslation("はい", "不是")
        assertNull(c.error); assertEquals("reversal", c.choice); assertEquals(0.85, c.confidence!!, 1e-9)
        // neither: not an answer the pipeline can weigh
        reply = { 200 to """{"answers":{"x":{"type":"choice","choice":"reversal"}}}""" }
        assertEquals("HTTP 200", Jev(JevProvider.CUSTOM, "", url = "http://127.0.0.1:$port/api/alpha/decisions", model = "m").mistranslation("はい", "不是").error)
    }

    @Test
    fun testingACustomServiceSaysWhatCameBack() {
        val ok = JevCustom(JevProtocol.SYSTEMONE, "http://127.0.0.1:$port/v0/management", "jev-tencent")
        reply = { 200 to """{"answers":{"x":{"type":"noul","noul":0.2}}}""" }
        assertTrue(Jev.test(ok, "").let { it.first && it.second.startsWith("连上了") })
        // the aigc gateway's occasional Spring 404, seen through CLIProxyAPI on 2026-10-21
        reply = { 404 to """{"timestamp":1792574070150,"status":404,"error":"Not Found","path":"/v1/systemone"}""" }
        assertEquals(false to """HTTP 404：{"timestamp":1792574070150,"status":404,"error":"Not Found","path":"/v1/systemone"}""", Jev.test(ok, "k"))
        reply = { 200 to """{"choices":[]}""" }
        assertTrue(Jev.test(ok, "").second.startsWith("服务回了 200，但不是 Jev 的回答"))
        assertEquals(false to "还没填模型", Jev.test(JevCustom(JevProtocol.SYSTEMONE, "http://h", ""), ""))
        assertEquals(false to "地址格式不对", Jev.test(JevCustom(JevProtocol.SYSTEMONE, "http://", "m"), ""))
    }

    @Test
    fun questionsFollowTheSessionLanguagesAndJapaneseToChineseKeepsTheTunedWording() {
        assertSame(Jev.UNFINISHED, Jev.unfinishedQuestion(Lang.JA))
        assertSame(Jev.MISTRANSLATION, Jev.mistranslationQuestion(Lang.JA, Target.ZH))
        val en = Jev.unfinishedQuestion(Lang.EN)["instructions"] as String
        assertTrue(en, en.contains("English livestream") && !en.contains("けど"))
        assertTrue((Jev.unfinishedQuestion(null)["instructions"] as String).contains("Japanese or English livestream"))
        val m = Jev.mistranslationQuestion(Lang.EN, Target.of(Lang.JA))["instructions"] as String
        assertTrue(m, m.contains("English VTuber") && m.contains("Japanese machine translation"))
        // a typed target is named as typed; a typed "中文" is not the tuned Japanese→Chinese check
        val c = Jev.mistranslationQuestion(Lang.JA, Target.custom("粤语")!!)["instructions"] as String
        assertTrue(c, c.contains("粤语 machine translation"))
        assertNotSame(Jev.MISTRANSLATION, Jev.mistranslationQuestion(Lang.JA, Target.custom("中文")!!))
        // the request sends the question of the configured pair
        reply = { 200 to """{"answers":{"x":{"type":"choice","choice":"ok","confidence":0.9}}}""" }
        Jev(JevProvider.ZEN, "", url = "http://127.0.0.1:$port/zen/v1/systemone", source = Lang.EN, target = Target.of(Lang.KO)).mistranslation("hi", "안녕")
        assertTrue(lastBody.get()!!, lastBody.get()!!.contains("Korean machine translation"))
    }

    @Test
    fun parsesTheOfficialResponseShape() {
        // recorded from api.typesafe.ai on 2026-10-10
        reply = { 200 to """{"model":"jev-1.13.0","answers":{"x":{"type":"noul","noul":0.66}},"usage":{"input_tokens":379,"output_tokens":49}}""" }
        val c = jev().unfinished("簡単な中国語読めますかって言われて簡単")
        assertEquals(0.66, c.noul!!, 1e-9); assertNull(c.error); assertEquals(200, c.status)
        assertEquals("Bearer sk-secret", lastAuth.get())
        val req = Json.parse(lastBody.get()!!) as Map<*, *>
        assertEquals("jev-latest", req["model"])
        assertEquals(mapOf("current" to "簡単な中国語読めますかって言われて簡単"), req["state"])
        assertFalse("key must not be in the logged request", c.request.contains("sk-secret"))
        assertEquals(c.request, lastBody.get())

        reply = { 200 to """{"model":"jev-1.13.0","answers":{"x":{"type":"choice","choice":"negation","confidence":0.96,"probabilities":{"negation":0.98,"ok":0.02}}}}""" }
        val m = jev().mistranslation("ダウンロードするなんて", "不要下载")
        assertEquals("negation", m.choice); assertEquals(0.96, m.confidence!!, 1e-9)
    }

    @Test
    fun httpErrorsAndBadBodiesGiveNoAnswerButKeepTheRawResponse() {
        reply = { 403 to """{"error":{"message":"Key limit exceeded (total limit).","code":403}}""" }
        val c = jev().unfinished("はい")
        assertNull(c.answer); assertEquals(403, c.status); assertTrue(c.response!!.contains("Key limit"))
        reply = { 200 to "not json" }
        val d = jev().unfinished("はい")
        assertNull(d.answer); assertEquals("not json", d.response)
        reply = { 200 to """{"answers":{}}""" }
        assertNull(jev().unfinished("はい").noul)
    }

    @Test
    fun theWholeCallIsBoundedByTheDeadline() {
        delayMs = 3_000
        val t0 = monoMs()
        val c = jev(deadline = 300).unfinished("はい")
        assertTrue("took ${monoMs() - t0} ms", monoMs() - t0 < 1_500)
        assertNull(c.answer); assertTrue(c.error!!, c.error!!.startsWith("deadline"))
    }

    @Test
    fun redirectsAreNotFollowed() {
        redirectTo = "http://127.0.0.1:$port/elsewhere"
        val c = jev().unfinished("はい")
        assertEquals(307, c.status); assertNull(c.answer); assertEquals(1, hits.get())
    }

    @Test
    fun cancelAbortsARequestInProgress() {
        delayMs = 3_000
        val j = jev(deadline = 5_000)
        val r = AtomicReference<JevCall?>()
        val t = Thread { r.set(j.unfinished("はい")) }.apply { start() }
        Thread.sleep(200)
        val t0 = monoMs(); j.cancel(); t.join(2_000)
        assertFalse(t.isAlive); assertTrue(monoMs() - t0 < 1_500)
        assertEquals("cancelled", r.get()!!.error)
        assertEquals("cancelled", j.unfinished("はい").error) // no new request after cancel
        assertEquals(1, hits.get())
    }

    @Test
    fun aCancelAfterTheCallIsRegisteredButBeforeItRunsSendsNothing() {
        lateinit var j: Jev
        // cancel lands after the request is registered (so cancel() sees it) and before it is executed
        j = Jev(JevProvider.OFFICIAL, "sk-secret", 2_000, url = "http://127.0.0.1:$port/v1/systemone", beforeSend = { j.cancel() })
        assertEquals("cancelled", j.unfinished("はい").error)
        Thread.sleep(200)
        assertEquals("no request may reach the server", 0, hits.get())
    }

    @Test
    fun aKeyWithControlCharactersNeverReachesTheLog() {
        val c = Jev(JevProvider.OFFICIAL, "sk-secret\naccident", 2_000, url = "http://127.0.0.1:$port/v1/systemone").unfinished("はい")
        assertNull(c.answer); assertEquals("bad key", c.error)
        assertEquals(0, hits.get())
        val logged = Json.encode(mapOf("request" to c.request, "response" to c.response, "error" to c.error))
        assertFalse(logged, logged.contains("sk-secret") || logged.contains("accident"))
    }

    @Test
    fun aServerEchoingTheKeyIsRedactedAndErrorsCarryNoMessage() {
        reply = { 401 to """{"error":"invalid key sk-secret"}""" }
        val c = jev().unfinished("はい")
        assertEquals(401, c.status); assertFalse(c.response!!, c.response!!.contains("sk-secret"))
        // a JSON-escaped echo is the same key
        reply = { 401 to """{"error":"invalid key \u0073k-secret","sk-secret":1}""" }
        val e = jev().unfinished("はい")
        assertFalse(e.response!!, e.response!!.contains("sk-secret") || Json.parse(e.response!!).toString().contains("sk-secret"))
        server.close() // connection refused: only the exception type is logged
        val d = jev().unfinished("はい")
        assertTrue(d.error!!, d.error!!.matches(Regex("[A-Za-z.]+")))
    }

    @Test
    fun jsonRoundTrip() {
        val v = mapOf("s" to "引号\"\\\n\u0001", "n" to 1, "d" to 0.5, "b" to true, "z" to null, "l" to listOf(1, "a"))
        val back = Json.parse(Json.encode(v)) as Map<*, *>
        assertEquals(v["s"], back["s"]); assertEquals(1, (back["n"] as Number).toInt()); assertEquals(0.5, (back["d"] as Number).toDouble(), 0.0)
        assertEquals(true, back["b"]); assertTrue(back.containsKey("z")); assertNull(back["z"])
        assertEquals(listOf(1, "a"), (back["l"] as List<*>).map { if (it is Number) it.toInt() else it })
        assertEquals("\u00e9x", Json.parse("\"\\u00e9x\""))
        // invalid input is an error, not a guess
        for (bad in listOf("{\"a\":1} x", "\"\\q\"", "{\"a\":}", "")) {
            assertTrue(bad, runCatching { Json.parse(bad) }.exceptionOrNull() is java.io.IOException)
        }
    }

    // ---- BYOK

    private fun byok(variant: ByokTranslator.ThinkingOff? = null, deadline: Long = 2_000) =
        ByokTranslator(ByokService("http://127.0.0.1:$port/v1/chat/completions", "m-1", "sk-test"), "SYS", variant, deadlineMs = deadline)
    private val plain = sse("""{"choices":[{"delta":{"content":"你好"},"finish_reason":"stop"}]}""")
    private val thought = sse("""{"choices":[{"delta":{"reasoning_content":"嗯…"}}]}""",
        """{"choices":[{"delta":{"content":"你好"},"finish_reason":"stop"}]}""")

    @Test
    fun byokSendsAFixedSystemMessageTheKeyAndTheFoundSwitch() {
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"你好"},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":90,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":64}}}""") }
        val r = byok(ByokTranslator.variant("thinking")).translate(byokUser("こんにちは"))!!
        assertTrue(r.error, r.usable); assertEquals("你好", r.text); assertEquals(64, r.cachedTokens); assertFalse(r.thinking)
        assertEquals("Bearer sk-test", lastAuth.get())
        val req = Json.parse(lastBody.get()!!) as Map<*, *>
        val msgs = req["messages"] as List<*>
        assertEquals("SYS", (msgs[0] as Map<*, *>)["content"]); assertEquals("system", (msgs[0] as Map<*, *>)["role"])
        assertEquals(mapOf("type" to "disabled"), req["thinking"]); assertEquals(true, req["stream"])
        // the key never reaches the log, even when the service echoes it
        reply = { 401 to """{"error":"bad key sk-test"}""" }
        val bad = byok().translate("x")!!
        assertFalse(bad.usable); assertNull("no BYOK reply body is kept", bad.response); assertEquals(401, bad.http)
    }

    @Test
    fun theProbeTakesTheFirstSwitchThatAnswersWithoutThinking() {
        // rejects the DeepSeek field, thinks with the template switch, is quiet with enable_thinking
        reply = { _ ->
            val b = lastBody.get()!!
            when {
                b.contains("\"thinking\":{") -> 400 to """{"error":"unknown field thinking"}"""
                b.contains("chat_template_kwargs") -> 200 to thought
                else -> 200 to plain
            }
        }
        val steps = mutableListOf<ByokTranslator.Step>()
        val v = byok().probe(steps)
        assertEquals("enable", v?.name); assertEquals(listOf("thinking", "template", "enable"), steps.map { it.variant.name })
        assertTrue(steps[1].result.thinking)
    }

    @Test
    fun aGemmaLikeServiceGetsMinimalEffortAndTheNoThinkingLine() {
        // Gemma 4 on Gemini's endpoint: unknown fields and effort "none" are refused, minimal is taken
        reply = { _ ->
            val b = lastBody.get()!!
            if (b.contains("\"reasoning_effort\":\"minimal\"")) 200 to plain else 400 to """{"error":"invalid argument"}"""
        }
        val steps = mutableListOf<ByokTranslator.Step>()
        val v = byok().probe(steps)
        assertEquals("minimal", v?.name)
        val sys = ((Json.parse(lastBody.get()!!) as Map<*, *>)["messages"] as List<*>).let { (it[0] as Map<*, *>)["content"] as String }
        assertTrue(sys, sys.startsWith("SYS\n\nCRITICAL: Do not think") && sys.endsWith("禁止输出任何思考过程与推理说明。"))
        assertFalse(sys, sys.contains("<|think|>")) // Gemma's own token would turn thinking on where it is parsed
        // the other switches do not touch the system message
        byok(ByokTranslator.variant("effort")).translate("x")
        assertEquals("SYS", ((Json.parse(lastBody.get()!!) as Map<*, *>)["messages"] as List<*>).let { (it[0] as Map<*, *>)["content"] })
    }

    @Test
    fun aServiceNoSwitchWorkedForIsTriedAgainOnceThereAreMore() {
        // stored before the minimal switch existed ("-"): never tested now, so the probe runs and can find it
        assertEquals(null, ByokTranslator.current("-"))
        assertEquals(null, ByokTranslator.current("no-such-switch"))
        assertEquals("-${ByokTranslator.THINKING_OFF.size}", ByokTranslator.current(ByokTranslator.NONE_WORKED))
        assertEquals("minimal", ByokTranslator.current("minimal"))
    }

    @Test
    fun generatingTermsUsesTheMinimalSwitchWithoutItsTranslationLine() {
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"配信→直播"},"finish_reason":"stop"}]}""") }
        val (got, err) = generateTerms(ByokService("http://127.0.0.1:$port/v1/chat/completions", "m-1", "sk-test"), "minimal", {},
            "测试", "", emptySet(), 5, Lang.JA, Target.ZH)
        assertEquals(err, listOf("配信" to "直播"), got)
        val b = Json.parse(lastBody.get()!!) as Map<*, *>
        assertEquals("minimal", b["reasoning_effort"])
        val sys = ((b["messages"] as List<*>)[0] as Map<*, *>)["content"] as String
        assertFalse(sys, sys.contains("CRITICAL") || sys.contains("直接输出翻译结果"))
    }

    @Test
    fun aGemmaThoughtChannelIsNeverShownAndAnEmptyOneIsNoThought() {
        // the empty channel a first chunk may carry even at minimal effort: the answer counts, not as thinking
        pieces = listOf("data: " + """{"choices":[{"delta":{"content":"<|chan"}}]}""" + "\n\n" to 0L,
            "data: " + """{"choices":[{"delta":{"content":"nel>thought<channel|>你好"},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n" to 0L)
        val e = byok().translate("x")!!
        assertTrue(e.error, e.usable); assertEquals("你好", e.text); assertFalse(e.thinking)
        // a channel with thoughts in it: flagged, stripped
        pieces = null
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"<|channel>thought想一下<channel|>你好"},"finish_reason":"stop"}]}""") }
        val t = byok().translate("x")!!
        assertEquals("你好", t.text); assertTrue(t.thinking)
        // one that never closed is no answer
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"<|channel>thought想了很久"},"finish_reason":"length"}]}""") }
        val u = byok().translate("x")!!
        assertFalse(u.usable); assertEquals("", u.text); assertEquals("unfinished thinking", u.error)
    }

    @Test
    fun theProbeStopsAtAWrongKeyAndReportsWhenNothingTurnsThinkingOff() {
        reply = { 401 to "{}" }
        val steps = mutableListOf<ByokTranslator.Step>()
        assertNull(byok().probe(steps)); assertEquals(1, steps.size)
        reply = { 200 to thought }
        steps.clear()
        assertNull(byok().probe(steps)); assertEquals(ByokTranslator.THINKING_OFF.size, steps.size)
        assertTrue(steps.all { it.result.usable && it.result.thinking })
        assertTrue(byok().exhausted(steps))
        // a rate limit half way: not "none works", try again next time
        reply = { _ -> if (lastBody.get()!!.contains("chat_template_kwargs")) 429 to "{}" else 200 to thought }
        steps.clear()
        assertNull(byok().probe(steps)); assertEquals(2, steps.size); assertFalse(byok().exhausted(steps))
        // a token-capped answer is not a complete one: the next switch is tried
        reply = { _ -> if (lastBody.get()!!.contains("\"thinking\":{")) 200 to sse("""{"choices":[{"delta":{"content":"你"},"finish_reason":"length"}]}""") else 200 to plain }
        steps.clear()
        assertEquals("template", byok().probe(steps)?.name)
        // one thinking answer, then server errors: inconclusive, probe again next time
        reply = { _ -> if (lastBody.get()!!.contains("\"thinking\":{")) 200 to thought else 503 to "{}" }
        steps.clear()
        assertNull(byok().probe(steps)); assertEquals(ByokTranslator.THINKING_OFF.size, steps.size); assertFalse(byok().exhausted(steps))
    }

    @Test
    fun byokNeverShowsReasoningOrTheKeyAndThinkingIsNotTheFirstText() {
        // the key echoed in the answer itself
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"key sk-test"},"finish_reason":"stop"}]}""") }
        val r = byok().translate("x")!!
        assertFalse(r.raw.contains("sk-test")); assertNull("a streamed body is never kept", r.response)
        // a header value is masked like the key (it may be one): split over chunks, and joined after reasoning is cut
        pieces = listOf("data: " + """{"choices":[{"delta":{"content":"好 hk-se"}}]}""" + "\n\n" to 0L,
            "data: " + """{"choices":[{"delta":{"content":"cr<think>x</think>et sk-test"},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n" to 0L)
        val h = ByokTranslator(ByokService("http://127.0.0.1:$port/v1/chat/completions", "m-1", "sk-test", headers = listOf("X-Api-Key" to "hk-secret")),
            "SYS", null).translate("x")!!
        pieces = null
        assertFalse(h.raw, h.raw.contains("hk-secret") || h.raw.contains("sk-test")); assertFalse(h.text.contains("hk-secret"))
        // ran out of tokens while thinking: nothing to show
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"<think>let me"},"finish_reason":"length"}]}""") }
        val t = byok().translate("x")!!
        assertFalse(t.usable); assertTrue(t.thinking)
        // thinking in the content at once, the translation only after 1 s: no translated text within 1 s
        pieces = listOf("data: " + """{"choices":[{"delta":{"content":"<think>嗯"}}]}""" + "\n\n" to 0L,
            "data: " + """{"choices":[{"delta":{"content":"</think>你好"},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n" to 1_500L)
        val late = ByokTranslator(ByokService("http://127.0.0.1:$port/v1/chat/completions", "m-1", "sk-test"), "SYS", null,
            deadlineMs = 4_000, firstContentMs = 1_000).translate("x")!!
        assertEquals("no content in 1000ms", late.error)
        // tags split over chunks: "<thi" + "nk>" is still thinking; a closed block then text in time is in time
        pieces = listOf("data: " + """{"choices":[{"delta":{"content":"<thi"}}]}""" + "\n\n" to 0L,
            "data: " + """{"choices":[{"delta":{"content":"nk>嗯</thi"}}]}""" + "\n\n" to 50L,
            "data: " + """{"choices":[{"delta":{"content":"nk>你好"}}]}""" + "\n\n" to 50L,
            "data: " + """{"choices":[{"delta":{},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n" to 1_200L)
        val split = ByokTranslator(ByokService("http://127.0.0.1:$port/v1/chat/completions", "m-1", "sk-test"), "SYS", null,
            deadlineMs = 4_000, firstContentMs = 1_000).translate("x")!!
        assertTrue(split.error, split.usable); assertEquals("你好", split.text)
        // the key split over two chunks: the body is not kept at all
        pieces = null
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"sk-"}}]}""", """{"choices":[{"delta":{"content":"test"},"finish_reason":"stop"}]}""") }
        val k = byok().translate("x")!!
        assertFalse(k.raw.contains("sk-test")); assertNull(k.response)
        // the key split inside reasoning: nothing of it is kept
        reply = { 200 to sse("""{"choices":[{"delta":{"reasoning_content":"sk-"}}]}""", """{"choices":[{"delta":{"reasoning_content":"test"}}]}""",
            """{"choices":[{"delta":{"content":"你好"},"finish_reason":"stop"}]}""") }
        val rc = byok().translate("x")!!
        assertNull(rc.response); assertEquals("你好", rc.text); assertTrue(rc.thinking)
        // a stream cut before [DONE]: failed, and its body is not kept either
        reply = { 200 to "data: " + """{"choices":[{"delta":{"content":"sk-test"}}]}""" + "\n\n" }
        val cut = byok().translate("x")!!
        assertFalse(cut.usable); assertNull(cut.response); assertFalse(cut.raw.contains("sk-test"))
        // removing a think block must not join the key back together
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"sk-<think>x</think>test"},"finish_reason":"stop"}]}""") }
        val joined = byok().translate("x")!!
        assertFalse(joined.text, joined.text.contains("sk-test")); assertFalse(joined.raw.contains("sk-test"))
        // thinking replies without finish_reason everywhere: inconclusive, not "none works"
        reply = { 200 to sse("""{"choices":[{"delta":{"reasoning_content":"x","content":"你好"}}]}""") }
        val steps = mutableListOf<ByokTranslator.Step>()
        assertNull(byok().probe(steps)); assertFalse(byok().exhausted(steps))
        // a nested closing tag that a second strip would turn into the key
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"sk-</thi</think>nk>test"},"finish_reason":"stop"}]}""") }
        val nested = byok().translate("x")!!
        assertFalse(nested.text, nested.text.contains("sk-test")); assertFalse(nested.raw.contains("sk-test"))
        // every switch runs out of tokens while thinking: conclusive, "none works" is kept
        reply = { 200 to sse("""{"choices":[{"delta":{"content":"<think>let me"},"finish_reason":"length"}]}""") }
        steps.clear()
        assertNull(byok().probe(steps)); assertTrue(byok().exhausted(steps))
    }

    @Test
    fun byokUrlsGetV1OnceAndKeepTheScheme() {
        val ok = "https://api.example.com/v1/chat/completions"
        for (u in listOf("https://api.example.com", "https://api.example.com/", "https://api.example.com/v1", "https://API.example.com/v1/",
            "https://api.example.com/v1/chat/completions")) assertEquals(u, ok, ByokTranslator.chatUrl(u).second)
        assertEquals("https://h.io:8443/api/openai/v1/chat/completions", ByokTranslator.chatUrl("https://h.io:8443/api/openai").second)
        // plain http is allowed too (a local or LAN endpoint), and the typed scheme comes back
        val plain = "http://192.168.1.7:8000/v1/chat/completions"
        for (u in listOf("http://192.168.1.7:8000", "http://192.168.1.7:8000/", "http://192.168.1.7:8000/v1", "HTTP://192.168.1.7:8000/v1/chat/completions"))
            assertEquals(u, plain, ByokTranslator.chatUrl(u).second)
        // an http address is not silently upgraded to https
        assertEquals("http://api.example.com/v1/chat/completions", ByokTranslator.chatUrl("http://api.example.com").second)
        for (u in listOf("", "https://u:p@h.io", "https://h.io/v1?x=1", "https://h.io/#a", "api.example.com"))
            assertNull(u, ByokTranslator.chatUrl(u).second)
        for (u in listOf("https://h.io:65536", "https://h.io:0")) assertNull(u, ByokTranslator.chatUrl(u).second)
        // any other scheme is refused, and the message names both that are accepted
        for (u in listOf("ftp://h.io", "file:///etc/hosts", "ws://h.io", "h.io"))
            assertTrue(u, ByokTranslator.chatUrl(u).first!!.contains("http:// 或 https://"))
        assertEquals("https://[::1]:8443/v1/chat/completions", ByokTranslator.chatUrl("HTTPS://[::1]:8443/v1/").second)
    }

    @Test
    fun byokPromptKeepsTheFixedPartFirstAndTheSentenceLast() {
        val a = byokSystem(Scene.VTUBER, Lang.JA, Target.ZH)
        assertTrue(a.contains("スパチャ→SC")); assertTrue(a.contains("背景"))
        assertFalse(byokSystem(Scene.VTUBER, Lang.JA, Target.of(Lang.EN)).contains("→SC")) // the built-in term list is Chinese
        val u = byokUser("今", listOf("note"), listOf("前1", "前2"))
        assertTrue(u.indexOf("前1") < u.indexOf("今")); assertTrue(u.indexOf("今") < u.indexOf("note"))
        assertEquals("【当前句】\n今", byokUser("今"))
    }

    // ---- 429: pause by service ----

    private val zenLimit = """{"type":"error","error":{"type":"FreeUsageLimitError","message":"Rate limit exceeded. Please try again later."}}"""
    private var now = 1_000_000L
    private var wallNow = 1_760_000_000_000L
    /** an HTTP IMF-fixdate (two-digit day, GMT), as a service sends it */
    private fun imfDate(t: java.time.temporal.TemporalAccessor) =
        java.time.format.DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).format(t)
    private fun timedJev(key: String = "sk-secret", provider: JevProvider = JevProvider.ZEN, deadline: Long = 2_000) =
        Jev(provider, key, deadline, url = "http://127.0.0.1:$port/v1/systemone", clock = { now }, wall = { wallNow })

    @Test
    fun aLimitWithRetryAfterSecondsPausesBothQuestionsWithoutSending() {
        reply = { 429 to zenLimit }; extraHeaders = "Retry-After: 46879\r\n"
        val j = timedJev()
        val first = j.unfinished("今日は")
        assertEquals(429, first.status)
        val l = first.limit!!
        assertTrue(l.free)
        assertEquals(now + 46_879_000, l.untilMs)
        assertEquals(wallNow + 46_879_000, l.resumeAt)
        // paused: nothing is sent, the call says so at once
        val u = j.unfinished("今日は"); val m = j.mistranslation("今日は", "今天")
        assertEquals(1, hits.get())
        assertEquals("paused", u.error); assertEquals("paused", m.error)
        assertNotNull(u.limit); assertNull(u.answer)
        assertNotNull(j.paused())
    }

    @Test
    fun aRetryAfterDateAndAMissingOneAreBothUsed() {
        reply = { 429 to """{"error":"slow down"}""" }
        val at = java.time.Instant.ofEpochMilli(wallNow + 90_000).atZone(java.time.ZoneOffset.UTC)
        extraHeaders = "Retry-After: ${imfDate(at)}\r\n"
        val l = timedJev().unfinished("a").limit!!
        assertFalse(l.free)
        assertEquals(now + 90_000, l.untilMs)
        // no header (another key, so another pause): the short backoff, no promised time
        extraHeaders = ""
        val b = timedJev("sk-other").unfinished("a").limit!!
        assertEquals(now + Jev.BACKOFF_S * 1000, b.untilMs)
        assertNull(b.resumeAt)
        assertNull(Jev.retrySeconds("soon", wallNow)); assertNull(Jev.retrySeconds("", wallNow))
        // "now" and a time already past: tried again at once, not after the backoff
        assertEquals(0L, Jev.retrySeconds("0", wallNow))
        val past = java.time.Instant.ofEpochMilli(wallNow - 90_000).atZone(java.time.ZoneOffset.UTC)
        assertEquals(0L, Jev.retrySeconds(imfDate(past), wallNow))
        // a wait of more than a day is kept as the service asks
        assertEquals(3 * 86_400L, Jev.retrySeconds("259200", wallNow))
        // all three HTTP-date forms, a day ahead and past
        val base = java.time.Instant.parse("2026-10-12T19:00:00Z").toEpochMilli()
        for (d in listOf("Tue, 13 Oct 2026 19:00:00 GMT", "Tuesday, 13-Oct-26 19:00:00 GMT", "Tue Oct 13 19:00:00 2026"))
            assertEquals(d, 86_400L, Jev.retrySeconds(d, base))
        for (d in listOf("Sun, 11 Oct 2026 19:00:00 GMT", "Sunday, 11-Oct-26 19:00:00 GMT", "Sun Oct 11 19:00:00 2026"))
            assertEquals(d, 0L, Jev.retrySeconds(d, base))
        // asctime pads a one-digit day with a space
        val nov3 = (java.time.Instant.parse("2026-11-03T19:00:00Z").toEpochMilli() - base) / 1000
        assertEquals(nov3, Jev.retrySeconds("Tue Nov  3 19:00:00 2026", base))
        assertNull(Jev.retrySeconds("Tue Oct  13 19:00:00 2026", base))
        assertNull(Jev.retrySeconds("13 Oct 2026", base))
        // impossible dates are no date (the short backoff), not the nearest real one
        for (d in listOf("Sun, 31 Feb 2027 19:00:00 GMT", "Sunday, 31-Feb-27 19:00:00 GMT", "Sun Feb 31 19:00:00 2027", "Sun Feb 29 19:00:00 2027"))
            assertNull(d, Jev.retrySeconds(d, base))
        // HTTP-dates are case-sensitive: other spellings are no date
        for (d in listOf("tuesday, 13-oct-26 19:00:00 gmt", "TUESDAY, 13-OCT-26 19:00:00 GMT", "Tuesday, 13-oct-26 19:00:00 GMT",
                "tue, 13 oct 2026 19:00:00 GMT", "Tue oct 13 19:00:00 2026"))
            assertNull(d, Jev.retrySeconds(d, base))
        // not the HTTP syntax (no seconds, no weekday, another zone, a sign, a one-digit IMF day): the short backoff
        for (d in listOf("Tue, 13 Oct 2026 19:00 GMT", "13 Oct 2026 19:00:00 GMT", "Tue, 13 Oct 2026 19:00:00 +0100", "+259200", "-5",
                "Tue, 3 Nov 2026 19:00:00 GMT"))
            assertNull(d, Jev.retrySeconds(d, base))
        // RFC 850: a two-digit year more than 50 years ahead is the century before
        assertEquals(0L, Jev.retrySeconds("Wednesday, 13-Oct-76 19:00:00 GMT", base)) // 1976, past
        val in2076 = java.time.LocalDateTime.of(2076, 10, 12, 19, 0)
        val d2076 = in2076.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)
        assertEquals((in2076.toInstant(java.time.ZoneOffset.UTC).toEpochMilli() - base) / 1000,
            Jev.retrySeconds("$d2076, 12-Oct-76 19:00:00 GMT", base)) // exactly 50 years ahead: 2076
    }

    @Test
    fun onlyZensOwnErrorTypeIsTheFreeLimitAndOnlyRealAnswersCount() {
        assertTrue(Jev.isZenFreeLimit(JevProvider.ZEN, zenLimit))
        assertFalse(Jev.isZenFreeLimit(JevProvider.OFFICIAL, zenLimit))
        assertFalse(Jev.isZenFreeLimit(JevProvider.ZEN, """{"error":{"type":"RateLimit","message":"not FreeUsageLimitError"}}"""))
        // 200 without a usable answer: no answer, so no success
        for (bad in listOf("""{"answers":{}}""", """{"answers":{"x":{"choice":"what"}}}""",
            """{"answers":{"x":{"choice":"ok"}}}""", """{"answers":{"x":{"choice":"ok","confidence":"high"}}}""")) {
            reply = { 200 to bad }
            val c = timedJev("sk-$bad").mistranslation("a", "b")
            assertNull(bad, c.answer); assertNotNull(c.error)
        }
        reply = { 200 to """{"answers":{"x":{"noul":7}}}""" }
        assertNull(timedJev("sk-n").unfinished("a").answer)
        reply = { 200 to """{"answers":{"x":{"choice":"ok","confidence":0.9}}}""" }
        assertEquals("ok", timedJev("sk-ok").mistranslation("a", "b").choice)
    }

    @Test
    fun thePauseOutlivesTheClientAndEndsOnTime() {
        reply = { if (it == 1) 429 to zenLimit else 200 to """{"answers":{"x":{"noul":0.9}}}""" }
        extraHeaders = "Retry-After: 30\r\n"
        timedJev().unfinished("a")
        // a setup rebuilt with the same service and key: a new client, still paused
        assertEquals("paused", timedJev().unfinished("a").error)
        // another key is not paused by it
        assertNull(timedJev("sk-b").paused())
        assertEquals(1, hits.get())
        now += 30_000
        val ok = timedJev().unfinished("a")
        assertEquals(0.9, ok.noul!!, 1e-9)
        assertNull(ok.limit)
        assertEquals(2, hits.get())
    }

    @Test
    fun aRequestHeldBeforeSendingIsNotSentOnceAPauseBegan() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        reply = { 429 to zenLimit }; extraHeaders = "Retry-After: 600\r\n"
        // passed the first check, then waits before it is sent (as in OkHttp's queue)
        val slow = Jev(JevProvider.ZEN, "sk-secret", 5_000, url = "http://127.0.0.1:$port/v1/systemone",
            beforeSend = { entered.countDown(); gate.await() }, clock = { now }, wall = { wallNow })
        val late = AtomicReference<JevCall>()
        val t = Thread { late.set(slow.unfinished("old")) }.apply { start() }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        timedJev().unfinished("a") // 429: paused
        assertEquals(1, hits.get())
        gate.countDown(); t.join(5_000)
        assertEquals("paused", late.get().error)
        assertEquals(1, hits.get()) // held back at the last check, nothing sent
    }

    @Test
    fun aLateAnswerOfARequestInFlightDoesNotEndOrShortenThePauseAndIsMarkedStale() {
        // request 1 is received by the server and its reply held; request 2 gets a 429 meanwhile
        holdHit = 1
        reply = { if (it == 1) 200 to """{"answers":{"x":{"noul":0.9}}}""" else 429 to zenLimit }
        extraHeaders = "Retry-After: 600\r\n"
        val j = timedJev()
        val late = AtomicReference<JevCall>()
        val t = Thread { late.set(j.unfinished("old")) }.apply { start() }
        assertTrue(held.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val limited = timedJev().unfinished("a")
        assertEquals(429, limited.status)
        holdGate.countDown(); t.join(5_000)
        // the old answer arrives fine, but it is from before the limit
        assertEquals(0.9, late.get().noul!!, 1e-9)
        assertTrue(j.limitedAfter(late.get().gen))
        assertEquals(1_000_000L + 600_000, j.paused()!!.untilMs)
        // also once a short pause is over: still stale (the generation stays)
        now += 600_000
        assertNull(j.paused())
        assertTrue(j.limitedAfter(late.get().gen))
        // a request after the pause is current again
        reply = { 200 to """{"answers":{"x":{"noul":0.2}}}""" }
        val fresh = j.unfinished("new")
        assertFalse(j.limitedAfter(fresh.gen))
    }

    @Test
    fun aRetryAfterOfZeroStillMakesOlderAnswersStale() {
        holdHit = 1
        reply = { if (it == 1) 200 to """{"answers":{"x":{"noul":0.9}}}""" else 429 to zenLimit }
        extraHeaders = "Retry-After: 0\r\n"
        val j = timedJev()
        val late = AtomicReference<JevCall>()
        val t = Thread { late.set(j.unfinished("old")) }.apply { start() }
        assertTrue(held.await(2, java.util.concurrent.TimeUnit.SECONDS))
        timedJev().unfinished("a")
        assertNull(j.paused()) // no pause at all
        holdGate.countDown(); t.join(5_000)
        assertTrue(j.limitedAfter(late.get().gen))
    }

    @Test
    fun theLastCheckRunsAfterTheConnectionIsMade() {
        // held in its DNS lookup (after the queue, before the connection); the check it then meets is the last one
        val dnsGate = java.util.concurrent.CountDownLatch(1)
        val inDns = java.util.concurrent.CountDownLatch(1)
        val dns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                inDns.countDown(); dnsGate.await(); return listOf(InetAddress.getByName("127.0.0.1"))
            }
        }
        val open = java.util.concurrent.atomic.AtomicBoolean(true)
        val r = AtomicReference<HttpResult>()
        val t = Thread {
            r.set(Calls(5_000, dns, java.net.Proxy.NO_PROXY).post("http://jev.test:$port/v1/systemone", "{}", emptyMap(), admit = { open.get() }))
        }.apply { start() }
        assertTrue(inDns.await(2, java.util.concurrent.TimeUnit.SECONDS))
        open.set(false) // a pause began while it waited for its connection
        dnsGate.countDown(); t.join(5_000)
        assertEquals("paused", r.get().error)
        assertEquals(0, hits.get())
    }

    /**
     * A 429 whose installation is held (in its wall-clock read, after the receipt was recorded) past the caller's
     * deadline, while both clocks move on by 2 s. [header] gets the wall time at receipt.
     */
    private fun heldInstall(header: (Long) -> String, check: (Jev, receivedAt: Long, receivedWall: Long) -> Unit) {
        val inCallback = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val callbackThread = AtomicReference<Thread>()
        reply = { if (it == 1) 429 to zenLimit else 200 to """{"answers":{"x":{"noul":0.2}}}""" }
        extraHeaders = "Retry-After: ${header(wallNow)}\r\n"
        val receivedAt = now; val receivedWall = wallNow
        val changes = java.util.concurrent.atomic.AtomicInteger()
        val key = "sk-${header(0).length}-$receivedAt"
        val j = Jev(JevProvider.ZEN, key, 600, url = "http://127.0.0.1:$port/v1/systemone",
            clock = { now }, wall = {
                if (Thread.currentThread().name.startsWith("OkHttp") && callbackThread.compareAndSet(null, Thread.currentThread())) {
                    inCallback.countDown(); release.await()
                }
                wallNow
            })
        val oldWatch = java.util.concurrent.atomic.AtomicInteger()
        val stopOld = j.watch { oldWatch.incrementAndGet() }
        val first = AtomicReference<JevCall>()
        val t0 = monoMs()
        val t1 = Thread { first.set(j.unfinished("a")) }.apply { start() }
        try {
            assertTrue(inCallback.await(2, java.util.concurrent.TimeUnit.SECONDS))
            // being installed already counts: the other question is not sent, and an answer from before is stale
            val other = j.unfinished("c")
            assertEquals("paused", other.error)
            assertNotNull(j.paused())
            assertTrue(j.limitedAfter(0))
            // the caller still gives up at its deadline, not when the installation is done, with the 429 it got
            t1.join(2_000)
            assertFalse(t1.isAlive)
            assertTrue("returned after ${monoMs() - t0} ms", monoMs() - t0 < 1_200)
            assertEquals(429, first.get().status)
            assertEquals(1, hits.get())
            assertEquals(0, changes.get())
            // the session stops, a new one (its own client of the same service) watches before the 429 is installed
            stopOld.close()
            val next = Jev(JevProvider.ZEN, key, 600, url = "http://127.0.0.1:$port/v1/systemone", clock = { now }, wall = { wallNow })
            next.watch { changes.incrementAndGet() }
            assertEquals(JevLimit.INSTALLING, next.paused())
            now += 2_000; wallNow += 2_000
        } finally { release.countDown(); t1.join(3_000) }
        val deadline = monoMs() + 2_000
        while (changes.get() == 0 && monoMs() < deadline) Thread.sleep(5)
        // the installation tells the new client's watcher, once, after its monitor (no request result); not the old one
        assertEquals(1, changes.get())
        assertEquals(0, oldWatch.get())
        assertEquals(1, hits.get())
        check(j, receivedAt, receivedWall)
        // one generation: a request let out after the pause is current
        now += 61_000; wallNow += 61_000
        val fresh = j.unfinished("b")
        assertEquals(200, fresh.status)
        Thread.sleep(100)
        assertFalse(j.limitedAfter(fresh.gen))
    }

    @Test
    fun delaySecondsCountFromTheReceiptAlsoWhenInstalledLate() = heldInstall({ "60" }) { j, at, atWall ->
        val l = j.paused()!!
        assertEquals(at + 60_000, l.untilMs)
        assertEquals(atWall + 60_000, l.resumeAt)
    }

    @Test
    fun anHttpDateEndsAtThatTimeAlsoWhenInstalledLate() = heldInstall({ w ->
        imfDate(java.time.Instant.ofEpochMilli(w + 60_000).atZone(java.time.ZoneOffset.UTC))
    }) { j, at, atWall ->
        val l = j.paused()!!
        assertEquals(at + 60_000, l.untilMs)
        assertEquals(atWall + 60_000, l.resumeAt)
    }

    @Test
    fun anInvalidDateInARealResponseIsTheShortBackoff() {
        reply = { 429 to """{"error":"slow down"}""" }
        extraHeaders = "Retry-After: Sun, 31 Feb 2027 19:00:00 GMT\r\n"
        val l = timedJev("sk-invalid-date").unfinished("a").limit!!
        assertEquals(now + Jev.BACKOFF_S * 1000, l.untilMs)
        assertNull(l.resumeAt)
    }

    @Test
    fun aPauseKeptAcrossSessionsEndsByAClockThatCountsSleep() {
        // the app's pause clock counts deep sleep; a new client for the same service, an hour later, may ask again
        reply = { if (it == 1) 429 to zenLimit else 200 to """{"answers":{"x":{"noul":0.2}}}""" }
        extraHeaders = "Retry-After: 60\r\n"
        var realtime = 5_000_000L
        val mk = { Jev(JevProvider.ZEN, "sk-sleep", 2_000, url = "http://127.0.0.1:$port/v1/systemone", clock = { realtime }, wall = { wallNow }) }
        assertNotNull(mk().unfinished("a").limit)
        realtime += 3_600_000 // asleep: elapsedRealtime moves, nanoTime would not
        val again = mk()
        assertNull(again.paused())
        assertEquals(200, again.unfinished("b").status)
    }

    @Test
    fun thePauseStartsWhenThe429HeadersArriveAndOutlastsASlowBody() {
        // headers at once, the body never completes before the deadline
        reply = { 429 to zenLimit }; extraHeaders = "Retry-After: 120\r\n"; trickleMs = 1_000
        val j = timedJev(deadline = 1_500)
        val first = AtomicReference<JevCall>()
        val t = Thread { first.set(j.unfinished("a")) }.apply { start() }
        val deadline = monoMs() + 1_000
        while (j.paused() == null && monoMs() < deadline) Thread.sleep(5)
        // the pause is on while the body still trickles: the other question is not sent
        assertNotNull(j.paused())
        assertEquals("paused", j.mistranslation("a", "b").error)
        t.join(5_000)
        assertEquals(1, hits.get())
        // the deadline result is still a 429 with its pause
        assertEquals(429, first.get().status)
        assertEquals(now + 120_000, first.get().limit!!.untilMs)
    }

    @Test
    fun aTranslationWhose429BodyIsSlowIsStillA429() {
        // the body trickles past the first-content cutoff (500 ms), well before the deadline: that exit keeps the 429
        reply = { 429 to """{"error":"slow down, this body takes a while"}""" }; trickleMs = 100
        val r = streamed(500, deadline = 10_000).translate("x")!!
        assertEquals(429, r.http)
        assertTrue(r.error!!, r.error!!.startsWith("no content"))
    }

    @Test fun aRouteThatFailsBeforeTheRequestIsSentGivesWayToTheNext() {
        // the system's proxy first, then direct (desktop Main.kt): a proxy that cannot be reached gives way, and the
        // request is sent once, on the direct route
        val closed = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        val bad = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", closed))
        val selector = object : java.net.ProxySelector() {
            override fun select(uri: java.net.URI) = listOf(bad, java.net.Proxy.NO_PROXY)
            override fun connectFailed(uri: java.net.URI, sa: java.net.SocketAddress, e: java.io.IOException) {}
        }
        reply = { _ -> 200 to "{}" }
        val r = Calls(5_000, proxySelector = selector).post("http://127.0.0.1:$port/v1/x", "{\"a\":1}", emptyMap())
        assertEquals(r.error, 200, r.status)
        assertEquals(1, hits.get())
    }

    @Test fun aRequestThatWasSentIsNeverSentAgain() {
        // the server reads the request and closes without an answer: no second request (it would be billed twice)
        val ss = ServerSocket(0)
        val seen = AtomicInteger()
        Thread {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                s.use { sock ->
                    val inp = sock.getInputStream(); val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) return@use; head.append(b.toChar()) }
                    seen.incrementAndGet()
                }
            }
        }.apply { isDaemon = true; start() }
        ss.use {
            val r = Calls(3_000, proxy = java.net.Proxy.NO_PROXY).post("http://127.0.0.1:${ss.localPort}/v1/x", "{}", emptyMap())
            assertEquals(0, r.status)
            Thread.sleep(200)
            assertEquals(1, seen.get())
        }
    }
}
