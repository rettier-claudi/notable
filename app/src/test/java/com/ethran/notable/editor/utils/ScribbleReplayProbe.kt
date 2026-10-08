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
            val pts = data.split(" ").map { val p = it.split(","); StrokePoint(p[0].toFloat(), p[1].toFloat()) }
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
            val axis = scribbleAxis(s.points) ?: continue
            val t = scribbleTargets(s.points, strokes.subList(0, i))
            out.append("$page $i $axis -> ${t.map { it.id.substringAfterLast("-") }}\n")
        }
        out.append("strokes $total\n")
        File(System.getenv("SCRIBBLE_PROBE_OUT")).writeText(out.toString())
    }
}
