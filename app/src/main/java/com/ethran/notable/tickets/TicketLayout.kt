package com.ethran.notable.tickets

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max
import kotlin.math.min

/**
 * Ticket pages (KD tickets from YouTrack, printed by the tablet bridge): the regions of one page as
 * `GET /gesten/<pageId>` delivers them. Coordinates are page coordinates, the same unit as the
 * stroke points (the bridge draws the page and reads the strokes in that unit). Spec:
 * tablet-bruecke `docs/ticket-seiten.md`, "Vertrag Client ↔ Brücke".
 */
data class Box(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val w: Float get() = x2 - x1
    val h: Float get() = y2 - y1
    val cx: Float get() = (x1 + x2) / 2
    val cy: Float get() = (y1 + y2) / 2

    fun grow(dx: Float, dy: Float = dx) = Box(x1 - dx, y1 - dy, x2 + dx, y2 + dy)
    fun contains(x: Float, y: Float) = x in x1..x2 && y in y1..y2
    fun union(o: Box) = Box(min(x1, o.x1), min(y1, o.y1), max(x2, o.x2), max(y2, o.y2))

    companion object {
        /** Normalised: the bridge may list a rect in any corner order. */
        fun of(a: Float, b: Float, c: Float, d: Float) = Box(min(a, c), min(b, d), max(a, c), max(b, d))
    }
}

enum class RegionArt(val wire: String) {
    KASTEN("kasten"), AMPEL("ampel"), ZEILE("zeile"), STAGE("stage"), GUERTEL("guertel");

    companion object {
        fun of(wire: String?): RegionArt? = entries.firstOrNull { it.wire == wire }
    }
}

data class Region(
    val ticket: String,
    val art: RegionArt,
    val box: Box,
    val wert: String? = null,
    val aktuell: Boolean = false,
)

data class TicketLayout(
    val pageId: String,
    val art: String,
    val stand: String?,
    val regionen: List<Region>,
) {
    /** Where the answer of a gesture on [ticket] goes: right of its row. */
    fun rowOf(ticket: String): Box? =
        regionen.firstOrNull { it.ticket == ticket && it.art == RegionArt.ZEILE }?.box
            ?: regionen.filter { it.ticket == ticket }.map { it.box }.reduceOrNull(Box::union)
}

private val layoutJson = Json { ignoreUnknownKeys = true }

/**
 * Parses the bridge's answer. Regions of an unknown kind, without a ticket or with a broken rect
 * are left out (a newer bridge may add kinds); null when the JSON itself is not a layout.
 */
fun parseTicketLayout(text: String, fallbackPageId: String): TicketLayout? = runCatching {
    val root = layoutJson.parseToJsonElement(text).jsonObject
    val regionen = (root["regionen"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val art = RegionArt.of(o["art"]?.jsonPrimitive?.contentOrNull) ?: return@mapNotNull null
        val ticket = o["ticket"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val r = (o["rect"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.floatOrNull }
        if (r == null || r.size != 4 || r.any { it.isNaN() || it.isInfinite() }) return@mapNotNull null
        val box = Box.of(r[0], r[1], r[2], r[3])
        if (box.w <= 0f || box.h <= 0f) return@mapNotNull null
        Region(
            ticket = ticket,
            art = art,
            box = box,
            wert = o["wert"]?.jsonPrimitive?.contentOrNull,
            aktuell = o["aktuell"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }
    TicketLayout(
        pageId = root["pageId"]?.jsonPrimitive?.contentOrNull ?: fallbackPageId,
        art = root["art"]?.jsonPrimitive?.contentOrNull ?: "",
        stand = root["stand"]?.jsonPrimitive?.contentOrNull,
        regionen = regionen,
    )
}.getOrNull()

/** The answer of `POST /geste`: `anzeige` is what goes next to the row. */
data class GestureAnswer(val ok: Boolean, val anzeige: String?, val grund: String?)

fun parseGestureAnswer(text: String): GestureAnswer? = runCatching {
    val o = layoutJson.parseToJsonElement(text).jsonObject
    GestureAnswer(
        ok = o["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
        anzeige = o["anzeige"]?.jsonPrimitive?.contentOrNull,
        grund = o["grund"]?.jsonPrimitive?.contentOrNull,
    )
}.getOrNull()
