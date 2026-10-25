package com.ethran.notable.tickets

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One recognised gesture on its way to the bridge; kept in the KV queue until it is answered. */
@Serializable
data class PendingGesture(
    val pageId: String,
    val strokeIds: List<String>,
    val ticket: String,
    val aktion: String,
    val wert: String? = null,
    /** Row box (page coordinates) the answer is drawn next to. */
    val anchor: List<Float>,
    val createdAt: Long,
) {
    val key: String get() = pageId + "|" + strokeIds.sorted().joinToString(",")
    fun anchorBox(): Box = Box(anchor[0], anchor[1], anchor[2], anchor[3])
}

sealed interface LayoutResult {
    data class Ticket(val layout: TicketLayout) : LayoutResult
    /** 404: an ordinary page. */
    data object NoTicketPage : LayoutResult
    /** Anything else (offline, 5xx, broken JSON, bad token): change nothing. */
    data class Failed(val reason: String) : LayoutResult
}

sealed interface PostResult {
    /** Answered (ok or not): show [anzeige], drop it from the queue. */
    data class Answered(val anzeige: String, val ok: Boolean) : PostResult
    /** Token refused: keep it queued, the user has to fix the token. */
    data object AuthFailed : PostResult
    /** Offline / server trouble: keep it queued and try again later. */
    data class Retry(val reason: String) : PostResult
}

/**
 * HTTP side of the ticket pages (spec tablet-bruecke `docs/ticket-seiten.md`, "Vertrag Client ↔
 * Brücke"): `GET /gesten/<pageId>` and `POST /geste`, header `X-Bruecke-Token`. Plain JVM, no
 * Android, so it is tested against a local server.
 */
class BrueckeClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    fun getLayout(baseUrl: String, token: String, pageId: String): LayoutResult {
        val url = endpoint(baseUrl, "gesten", pageId) ?: return LayoutResult.Failed("bad URL")
        val request = Request.Builder().url(url).header(TOKEN_HEADER, token).get().build()
        return try {
            http.newCall(request).execute().use { r ->
                when {
                    r.code == 404 -> LayoutResult.NoTicketPage
                    r.isSuccessful -> parseTicketLayout(r.body.string(), pageId)
                        ?.let { LayoutResult.Ticket(it) } ?: LayoutResult.Failed("unreadable layout")
                    else -> LayoutResult.Failed("HTTP ${r.code}")
                }
            }
        } catch (e: IOException) {
            LayoutResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun postGesture(baseUrl: String, token: String, g: PendingGesture): PostResult {
        val url = endpoint(baseUrl, "geste") ?: return PostResult.Retry("bad URL")
        val request = Request.Builder().url(url).header(TOKEN_HEADER, token)
            .post(gestureBody(g).toRequestBody(JSON)).build()
        return try {
            http.newCall(request).execute().use { r -> postOutcome(r.code, r.body.string()) }
        } catch (e: IOException) {
            PostResult.Retry(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        const val TOKEN_HEADER = "X-Bruecke-Token"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** `<base>/<segments…>`, each segment path-encoded; null for an unusable base URL. */
        fun endpoint(baseUrl: String, vararg segments: String): okhttp3.HttpUrl? {
            val base = baseUrl.trim().toHttpUrlOrNull() ?: return null
            val b = base.newBuilder()
            // A trailing slash leaves an empty last segment; drop it so "x/" and "x" mean the same.
            if (base.pathSegments.lastOrNull() == "") b.removePathSegment(base.pathSegments.size - 1)
            segments.forEach { b.addPathSegment(it) }
            return b.build()
        }

        /** Body of `POST /geste`; `wert` only for stage/guertel, as the contract says. */
        fun gestureBody(g: PendingGesture): String = buildJsonObject {
            put("pageId", g.pageId)
            putJsonArray("strokeIds") { g.strokeIds.forEach { add(it) } }
            put("ticket", g.ticket)
            put("aktion", g.aktion)
            if (g.aktion == GestureAction.STAGE.wire || g.aktion == GestureAction.GUERTEL.wire) {
                g.wert?.let { put("wert", it) }
            }
        }.toString()

        fun postOutcome(code: Int, body: String): PostResult {
            val answer = parseGestureAnswer(body)
            return when {
                code == 401 || code == 403 -> PostResult.AuthFailed
                code == 408 || code == 429 || code >= 500 -> PostResult.Retry("HTTP $code")
                code in 200..299 && answer != null ->
                    PostResult.Answered(answer.anzeige ?: if (answer.ok) "✓" else "✗", answer.ok)
                code in 200..299 -> PostResult.Answered("✓", true)
                // Another 4xx: the bridge refused this gesture for good; sending it again changes nothing.
                else -> PostResult.Answered(answer?.anzeige ?: "✗", false)
            }
        }
    }
}
