package br.com.emanuel.navegador

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.core.graphics.PathParser

/** Small vector controls; no bitmap allocation, icon fonts or network assets. */
class Glyph(context: Context, name: String, private val color: Int) : View(context) {
    private val path = PathParser.createPathFromPathData(paths[name] ?: paths.getValue("globe"))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.65f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.scale(width / 24f, height / 24f)
        paint.color = color; path?.let { canvas.drawPath(it, paint) }; canvas.restore()
    }
    companion object {
        val paths = mapOf(
            "back" to "M19,12H5M12,5L5,12L12,19",
            "forward" to "M5,12H19M12,5L19,12L12,19",
            "home" to "M3,10L12,3L21,10V21H15V14H9V21H3Z",
            "menu" to "M4,6H20M4,12H20M4,18H20",
            "tabs" to "M6,3H18Q21,3 21,6V18Q21,21 18,21H6Q3,21 3,18V6Q3,3 6,3Z",
            "close" to "M6,6L18,18M18,6L6,18",
            "plus" to "M12,4V20M4,12H20",
            "compass" to "M12,2A10,10 0,1 1,12,22A10,10 0,1 1,12,2M16,7L13.5,13.5L7,17L10,10Z",
            "search" to "M10.5,3A7.5,7.5 0,1 1,10.5,18A7.5,7.5 0,1 1,10.5,3M16,16L21,21",
            "lock" to "M7,10V7A5,5 0,0 1,17,7V10M5,10H19V21H5ZM12,14V17",
            "warning" to "M12,3L22,21H2ZM12,9V14M12,17V18",
            "globe" to "M12,2A10,10 0,1 1,12,22A10,10 0,1 1,12,2M2,12H22M12,2C5,7 5,17 12,22M12,2C19,7 19,17 12,22",
            "star" to "M12,3L15,9L22,10L17,15L18,22L12,18L6,22L7,15L2,10L9,9Z",
            "history" to "M3,11A9,9 0,1 1,5,18M3,4V11H10M12,7V12L16,14",
            "download" to "M12,3V15M7,10L12,15L17,10M4,16V21H20V16",
            "share" to "M12,16V3M7,8L12,3L17,8M5,12H3V21H21V12H19",
            "reload" to "M20,10A8.5,8.5 0,1 0,20,16M20,3V10H13",
            "settings" to "M4,6H20M4,12H20M4,18H20M8,3V9M16,9V15M9,15V21",
            "shield" to "M12,2L21,6V12C21,17 16,21 12,23C8,21 3,17 3,12V6ZM8,12L11,15L16,9",
            "desktop" to "M3,3H21V16H3ZM12,16V21M7,21H17",
            "private" to "M3,7L12,4L21,7V15L18,19H14L12,16L10,19H6L3,15ZM6,10L10,11M14,11L18,10",
            "file" to "M5,2H14L20,8V22H5ZM14,2V8H20M8,13H17M8,17H15",
            "check" to "M4,12L9,17L20,6"
        )
    }
}
