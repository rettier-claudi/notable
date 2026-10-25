package com.ethran.notable.tickets

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

/** The HTTP contract with the bridge (`docs/ticket-seiten.md`), against a local mock server. */
class BrueckeClientTest {

    private lateinit var server: HttpServer
    private val requests = ConcurrentLinkedQueue<Triple<String, String?, String>>()
    private var layoutStatus = 200
    private var postStatus = 200
    private var postBody = """{"ok": true, "anzeige": "→ 🥋"}"""

    private val layoutJson = """
        {"pageId": "p1", "art": "keikoba", "stand": "2026-10-25T08:00:00+02:00",
         "regionen": [
           {"ticket": "KD-105", "art": "kasten", "rect": [120, 414, 166, 460]},
           {"ticket": "KD-105", "art": "ampel", "rect": [186, 419, 222, 455], "wert": "🟢 始め"},
           {"ticket": "KD-105", "art": "zeile", "rect": [100, 400, 1740, 470]},
           {"ticket": "KD-105", "art": "stage", "wert": "🥋 稽古", "aktuell": true, "rect": [1404, 411, 1452, 459]},
           {"ticket": "KD-76",  "art": "guertel", "wert": "⚔️ 型", "aktuell": false, "rect": [1532, 491, 1484, 539]},
           {"ticket": "KD-77",  "art": "sonstwas", "rect": [0, 0, 10, 10]},
           {"ticket": "KD-78",  "art": "kasten", "rect": [0, 0, 10]}
         ]}
    """.trimIndent()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += Triple("${ex.requestMethod} ${ex.requestURI.path}", ex.requestHeaders.getFirst("X-Bruecke-Token"), body)
            val (code, text) = when {
                ex.requestURI.path.startsWith("/bruecke/gesten/") ->
                    if (layoutStatus == 200) 200 to layoutJson else layoutStatus to "nope"
                ex.requestURI.path == "/bruecke/geste" -> postStatus to postBody
                ex.requestURI.path == "/bruecke/aktiv" -> 200 to """{"ok": true}"""
                else -> 404 to ""
            }
            val bytes = text.toByteArray()
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private val base get() = "http://127.0.0.1:${server.address.port}/bruecke/"
    private val client = BrueckeClient()

    private val gesture = PendingGesture(
        pageId = "p1", strokeIds = listOf("s1", "s2"), ticket = "KD-105", aktion = "stage",
        wert = "🥋 稽古", anchor = listOf(100f, 400f, 1740f, 470f), createdAt = 0L,
    )

    @Test
    fun `layout is fetched with the token and parsed`() {
        val r = client.getLayout(base, "geheim", "p1") as LayoutResult.Ticket
        assertEquals(Triple("GET /bruecke/gesten/p1", "geheim", ""), requests.single())
        val l = r.layout
        assertEquals("keikoba", l.art)
        assertEquals("unknown kinds and broken rects are left out", 5, l.regionen.size)
        val belt = l.regionen.single { it.art == RegionArt.GUERTEL }
        assertEquals("rect normalised", Box(1484f, 491f, 1532f, 539f), belt.box)
        assertEquals("⚔️ 型", belt.wert)
        assertTrue(l.regionen.single { it.art == RegionArt.STAGE }.aktuell)
        assertEquals("answer goes next to the row", Box(100f, 400f, 1740f, 470f), l.rowOf("KD-105"))
        assertEquals("no row: union of the ticket's regions", belt.box, l.rowOf("KD-76"))
    }

    @Test
    fun `404 is an ordinary page, other errors change nothing`() {
        layoutStatus = 404
        assertEquals(LayoutResult.NoTicketPage, client.getLayout(base, "t", "p1"))
        layoutStatus = 500
        assertTrue(client.getLayout(base, "t", "p1") is LayoutResult.Failed)
        layoutStatus = 401
        assertTrue(client.getLayout(base, "t", "p1") is LayoutResult.Failed)
        server.stop(0)
        assertTrue("offline", client.getLayout(base, "t", "p1") is LayoutResult.Failed)
        start()
    }

    @Test
    fun `activity carries the strokes with whole-pixel points`() {
        val s = stroke(10.4f to 20.6f, 12f to 22f, id = "w1", step = 100f)
        assertTrue(client.postActivity(base, "geheim", "p1", listOf(s)))
        val (what, token, body) = requests.single()
        assertEquals("POST /bruecke/aktiv", what)
        assertEquals("geheim", token)
        assertEquals("""{"pageId":"p1","striche":[{"id":"w1","punkte":[[10,21],[12,22]]}]}""", body)
        server.stop(0)
        assertTrue("offline is no error, just false", !client.postActivity(base, "geheim", "p1", listOf(s)))
        start()
    }

    @Test
    fun `gesture is posted as the contract says`() {
        val r = client.postGesture(base, "geheim", gesture)
        assertEquals(PostResult.Answered("→ 🥋", true), r)
        val (what, token, body) = requests.single()
        assertEquals("POST /bruecke/geste", what)
        assertEquals("geheim", token)
        assertEquals(
            """{"pageId":"p1","strokeIds":["s1","s2"],"ticket":"KD-105","aktion":"stage","wert":"🥋 稽古"}""",
            body,
        )
    }

    @Test
    fun `wert only goes with stage and belt`() {
        val body = BrueckeClient.gestureBody(gesture.copy(aktion = "weiter", wert = "x"))
        assertEquals("""{"pageId":"p1","strokeIds":["s1","s2"],"ticket":"KD-105","aktion":"weiter"}""", body)
    }

    @Test
    fun `answers map to show, wait or fix the token`() {
        postBody = """{"ok": false, "anzeige": "✗", "grund": "Stage unbekannt"}"""
        assertEquals(PostResult.Answered("✗", false), client.postGesture(base, "t", gesture))
        postStatus = 503
        assertTrue(client.postGesture(base, "t", gesture) is PostResult.Retry)
        postStatus = 401
        assertEquals(PostResult.AuthFailed, client.postGesture(base, "t", gesture))
        postStatus = 422
        postBody = "kaputt"
        assertEquals(PostResult.Answered("✗", false), client.postGesture(base, "t", gesture))
        postStatus = 200
        postBody = ""
        assertEquals(PostResult.Answered("✓", true), client.postGesture(base, "t", gesture))
        server.stop(0)
        assertTrue("offline waits", client.postGesture(base, "t", gesture) is PostResult.Retry)
        start()
    }

    @Test
    fun `base URL with or without slash`() {
        assertEquals("http://h/b/geste", BrueckeClient.endpoint("http://h/b", "geste").toString())
        assertEquals("http://h/b/geste", BrueckeClient.endpoint("http://h/b/", "geste").toString())
        assertEquals("http://h/gesten/a%20b", BrueckeClient.endpoint("http://h", "gesten", "a b").toString())
        assertNull(BrueckeClient.endpoint("kein url", "geste"))
    }

    @Test
    fun `a second tick in the same box soon after is the same mark`() {
        val ticks = RecentTicks()
        val box = region("KD-105", RegionArt.KASTEN, 120, 414, 166, 460)
        assertTrue(ticks.accept("p", box, 1_000))
        assertTrue(!ticks.accept("p", box, 3_000))
        assertTrue(ticks.accept("p", box, 3_000 + RecentTicks.WINDOW_MS + 1))
        assertTrue("another box", ticks.accept("p", box.copy(ticket = "KD-1"), 3_500))
    }
}
