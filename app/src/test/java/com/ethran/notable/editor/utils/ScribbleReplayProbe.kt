package com.ethran.notable.editor.utils

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import org.junit.Test
import java.io.File

/** Not a test: replays a stroke dump (page|index|x,y,dt ...) when SCRIBBLE_PROBE is set. */
class ScribbleReplayProbe {
    @Test
    fun probe() {
        val path = System.getenv("SCRIBBLE_PROBE") ?: return
        val pages = File(path).readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val (page, index, data) = line.split("|")
            val pts = data.split(" ").map { val p = it.split(","); StrokePoint(p[0].toFloat(), p[1].toFloat(), dt = p.getOrNull(2)?.toInt()?.toUShort()) }
            page to Stroke(
                id = "$page-$index", size = 3f, pen = Pen.FOUNTAIN,
                top = pts.minOf { it.y } - 3f, bottom = pts.maxOf { it.y } + 3f,
                left = pts.minOf { it.x } - 3f, right = pts.maxOf { it.x } + 3f,
                points = pts, pageId = page,
            )
        }.groupBy({ it.first }, { it.second })
        val out = StringBuilder()
        var total = 0
        for ((page, strokes) in pages) for ((i, s) in strokes.withIndex()) {
            total++
            val before = strokes.subList(0, i)
            // Like the tablet: live check point by point (80 ms apart on the pen's clock), then pen-up.
            val check = LiveScribbleCheck()
            s.points.forEach { p -> check.add(p, (p.dt?.toLong() ?: 0L)) { before } }
            val hit = check.hit
            val t = if (hit != null) confirmedScribbleTargets(s.points, before, hit) else scribbleTargets(s.points, before)
            if (hit == null && t.isEmpty()) continue
            out.append("$page $i red=${hit != null} -> ${t.map { it.id.substringAfterLast("-") }}\n")
        }
        out.append("strokes $total\n")
        File(System.getenv("SCRIBBLE_PROBE_OUT")).writeText(out.toString())
    }
}
