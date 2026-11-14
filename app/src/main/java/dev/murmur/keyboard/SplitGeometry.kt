package dev.murmur.keyboard

import helium314.keyboard.keyboard.Key.KeyParams
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.latin.common.Constants

/**
 * Murmur's split keyboard (design: "Murmur on the inner screen" artifact).
 *
 * Instead of stretching each row around a spacer (which bent the row stagger backwards), each half is the
 * normal keyboard shrunk toward its outer edge. So:
 *  - rows keep the real stagger: Q, then A half a key in, then Z;
 *  - keys are about as wide as on the cover screen (44 dp from key to key);
 *  - each half sits [INSET_DP] in from the screen edge, into the thumb's reach;
 *  - the space bar crosses the middle, so it's one bar either thumb can hit;
 *  - the last letter of the left half in the middle and bottom letter rows (G and B on QWERTY)
 *    also starts the right half, so a reach that overshoots still lands on the right letter.
 */
object SplitGeometry {
    private const val PITCH_DP = 44f   // key to key, matches the cover screen
    private const val INSET_DP = 36f   // from the screen edge to each half
    private const val MIN_GAP = 0.12f  // of the keyboard width, between the halves

    fun apply(params: KeyboardParams, rows: List<ArrayList<KeyParams>>, density: Float, gapScale: Float = 1f) {
        // the "Gap between halves" setting: wider moves the halves toward the edges and makes keys a little smaller
        val g = gapScale.coerceIn(0.4f, 2f)
        val width = params.mOccupiedWidth.toFloat()
        val left0 = params.mLeftPadding.toFloat()
        val base = params.mBaseWidth.toFloat()
        val center = left0 + base / 2
        val inset = (INSET_DP / g * density).coerceAtMost(width * 0.05f)
        // a normal row is 10 keys across the base width
        val scale = (PITCH_DP * (1 - (g - 1) * 0.15f) * density * 10 / base).coerceAtMost((width - 2 * inset - MIN_GAP * width) / base)
        if (scale <= 0.2f) return

        // gaps between keys shrink with the keys
        params.mRelativeHorizontalGap *= scale
        params.mHorizontalGap = (params.mRelativeHorizontalGap * params.mId.width).toInt()

        fun leftX(x: Float) = inset + (x - left0) * scale
        fun rightX(x: Float) = width - inset - (left0 + base - x) * scale

        val letterRows = rows.filter { row -> row.count { !it.isSpacer && it.mCode > 0 && Character.isLetter(it.mCode) } >= 5 }
        rows.forEach { row ->
            if (row.isEmpty()) return@forEach
            var leftEnd = 0f
            var firstRight: KeyParams? = null
            var lastLeftLetter: KeyParams? = null
            row.forEach { key ->
                val x = key.xPos
                val end = x + key.mAbsoluteWidth
                val straddles = x < center && end > center && key.mCode == Constants.CODE_SPACE
                when {
                    straddles -> { key.xPos = leftX(x); key.mAbsoluteWidth = rightX(end) - key.xPos }
                    x + key.mAbsoluteWidth / 3 <= center -> { // same rule HeliBoard used: V and G stay left
                        key.xPos = leftX(x); key.mAbsoluteWidth *= scale
                        leftEnd = key.xPos + key.mAbsoluteWidth
                        if (!key.isSpacer && key.mCode > 0 && Character.isLetter(key.mCode)) lastLeftLetter = key
                    }
                    else -> {
                        key.xPos = rightX(x); key.mAbsoluteWidth *= scale
                        if (firstRight == null && !key.isSpacer) firstRight = key
                    }
                }
            }
            // the shared key: only in letter rows after the first, and only where it fits in the gap
            val dupSource = lastLeftLetter ?: return@forEach
            val right = firstRight ?: return@forEach
            val index = letterRows.indexOf(row)
            if (index < 1) return@forEach
            val dup = KeyParams(dupSource)
            dup.yPos = dupSource.yPos
            dup.mAbsoluteHeight = dupSource.mAbsoluteHeight
            dup.mAbsoluteWidth = dupSource.mAbsoluteWidth
            dup.xPos = right.xPos - dup.mAbsoluteWidth
            if (dup.xPos > leftEnd + dup.mAbsoluteWidth) row.add(row.indexOf(right), dup)
        }
    }
}
