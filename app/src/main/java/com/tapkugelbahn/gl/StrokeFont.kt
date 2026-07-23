package com.tapkugelbahn.gl

/**
 * A tiny neon vector (stroke) font — digits via seven-segments, letters as
 * hand-laid polylines on a 0..4 x, 0..6 y grid (y up). Everything the HUD shows
 * is drawn as glowing line segments, in keeping with the game's vector look.
 */
object StrokeFont {

    // Seven-segment digit table: a b c d e f g.
    private val SEG = arrayOf(
        // segment endpoints on the 0..3 x, 0..6 y grid
        intArrayOf(0, 6, 3, 6), // a top
        intArrayOf(3, 6, 3, 3), // b upper-right
        intArrayOf(3, 3, 3, 0), // c lower-right
        intArrayOf(0, 0, 3, 0), // d bottom
        intArrayOf(0, 3, 0, 0), // e lower-left
        intArrayOf(0, 6, 0, 3), // f upper-left
        intArrayOf(0, 3, 3, 3), // g middle
    )
    private val DIGIT = arrayOf(
        booleanArrayOf(true, true, true, true, true, true, false),   // 0
        booleanArrayOf(false, true, true, false, false, false, false), // 1
        booleanArrayOf(true, true, false, true, true, false, true),  // 2
        booleanArrayOf(true, true, true, true, false, false, true),  // 3
        booleanArrayOf(false, true, true, false, false, true, true), // 4
        booleanArrayOf(true, false, true, true, false, true, true),  // 5
        booleanArrayOf(true, false, true, true, true, true, true),   // 6
        booleanArrayOf(true, true, true, false, false, false, false),// 7
        booleanArrayOf(true, true, true, true, true, true, true),    // 8
        booleanArrayOf(true, true, true, true, false, true, true),   // 9
    )

    private val LETTERS: Map<Char, Array<IntArray>> = mapOf(
        'A' to arrayOf(intArrayOf(0, 0, 2, 6, 4, 0), intArrayOf(1, 2, 3, 2)),
        'B' to arrayOf(intArrayOf(0, 0, 0, 6, 2, 6, 3, 5, 3, 4, 2, 3, 0, 3), intArrayOf(2, 3, 3, 2, 3, 1, 2, 0, 0, 0)),
        'C' to arrayOf(intArrayOf(4, 5, 3, 6, 1, 6, 0, 5, 0, 1, 1, 0, 3, 0, 4, 1)),
        'D' to arrayOf(intArrayOf(0, 0, 0, 6, 2, 6, 4, 4, 4, 2, 2, 0, 0, 0)),
        'E' to arrayOf(intArrayOf(4, 6, 0, 6, 0, 0, 4, 0), intArrayOf(0, 3, 3, 3)),
        'F' to arrayOf(intArrayOf(4, 6, 0, 6, 0, 0), intArrayOf(0, 3, 3, 3)),
        'G' to arrayOf(intArrayOf(4, 5, 3, 6, 1, 6, 0, 5, 0, 1, 1, 0, 3, 0, 4, 1, 4, 3, 2, 3)),
        'H' to arrayOf(intArrayOf(0, 0, 0, 6), intArrayOf(4, 0, 4, 6), intArrayOf(0, 3, 4, 3)),
        'I' to arrayOf(intArrayOf(1, 6, 3, 6), intArrayOf(2, 6, 2, 0), intArrayOf(1, 0, 3, 0)),
        'J' to arrayOf(intArrayOf(3, 6, 3, 1, 2, 0, 1, 0, 0, 1)),
        'K' to arrayOf(intArrayOf(0, 0, 0, 6), intArrayOf(4, 6, 0, 3, 4, 0)),
        'L' to arrayOf(intArrayOf(0, 6, 0, 0, 4, 0)),
        'M' to arrayOf(intArrayOf(0, 0, 0, 6, 2, 3, 4, 6, 4, 0)),
        'N' to arrayOf(intArrayOf(0, 0, 0, 6, 4, 0, 4, 6)),
        'O' to arrayOf(intArrayOf(1, 0, 3, 0, 4, 1, 4, 5, 3, 6, 1, 6, 0, 5, 0, 1, 1, 0)),
        'P' to arrayOf(intArrayOf(0, 0, 0, 6, 3, 6, 4, 5, 4, 4, 3, 3, 0, 3)),
        'Q' to arrayOf(intArrayOf(1, 0, 3, 0, 4, 1, 4, 5, 3, 6, 1, 6, 0, 5, 0, 1, 1, 0), intArrayOf(2, 2, 4, 0)),
        'R' to arrayOf(intArrayOf(0, 0, 0, 6, 3, 6, 4, 5, 4, 4, 3, 3, 0, 3), intArrayOf(2, 3, 4, 0)),
        'S' to arrayOf(intArrayOf(4, 5, 3, 6, 1, 6, 0, 5, 1, 3, 3, 3, 4, 1, 3, 0, 1, 0, 0, 1)),
        'T' to arrayOf(intArrayOf(0, 6, 4, 6), intArrayOf(2, 6, 2, 0)),
        'U' to arrayOf(intArrayOf(0, 6, 0, 1, 1, 0, 3, 0, 4, 1, 4, 6)),
        'V' to arrayOf(intArrayOf(0, 6, 2, 0, 4, 6)),
        'W' to arrayOf(intArrayOf(0, 6, 1, 0, 2, 3, 3, 0, 4, 6)),
        'X' to arrayOf(intArrayOf(0, 0, 4, 6), intArrayOf(0, 6, 4, 0)),
        'Y' to arrayOf(intArrayOf(0, 6, 2, 3, 4, 6), intArrayOf(2, 3, 2, 0)),
        'Z' to arrayOf(intArrayOf(0, 6, 4, 6, 0, 0, 4, 0)),
        '!' to arrayOf(intArrayOf(2, 6, 2, 2), intArrayOf(2, 1, 2, 0)),
        '.' to arrayOf(intArrayOf(2, 0, 2, 1)),
        '-' to arrayOf(intArrayOf(1, 3, 3, 3)),
    )

    const val ADVANCE = 5f // grid units per character (incl. gap)

    fun width(text: String, scale: Float) = text.length * ADVANCE * scale

    interface LineSink { fun line(x0: Float, y0: Float, x1: Float, y1: Float) }

    /** Emit `text`'s strokes to [sink]; (x,y) is the baseline-left, y is screen-down. */
    fun draw(text: String, x: Float, y: Float, scale: Float, sink: LineSink) {
        var cx = x
        for (ch in text.uppercase()) {
            when {
                ch in '0'..'9' -> {
                    val on = DIGIT[ch - '0']
                    for (i in SEG.indices) if (on[i]) {
                        val s = SEG[i]
                        sink.line(cx + s[0] * scale, y - s[1] * scale, cx + s[2] * scale, y - s[3] * scale)
                    }
                }
                ch == ' ' -> {}
                else -> LETTERS[ch]?.forEach { poly ->
                    var k = 0
                    while (k + 3 < poly.size) {
                        sink.line(cx + poly[k] * scale, y - poly[k + 1] * scale, cx + poly[k + 2] * scale, y - poly[k + 3] * scale)
                        k += 2
                    }
                }
            }
            cx += ADVANCE * scale
        }
    }
}
