package dev.murmur.keyboard

import android.content.Context
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.latin.settings.Settings
import kotlin.math.roundToInt

/**
 * Murmur: number and phone pads sized like a phone dialer, not stretched to fill the keyboard.
 *  - rows are as tall as letter rows (HeliBoard gave 4 number rows the height of 5 letter rows);
 *  - the pad is at most [MAX_WIDTH_DP] wide and centered, so the inner screen doesn't get 270 px keys.
 * The full numpad (from the symbols page) has extra columns, so it gets a wider cap.
 */
object NumberPad {
    private const val MAX_WIDTH_DP = 420f
    private const val MAX_WIDTH_NUMPAD_DP = 560f

    fun applyWidth(params: KeyboardParams, ctx: Context) {
        val element = params.mId.element
        if (!element.isNumberLayout) return
        val cap = (if (element == KeyboardElement.NUMPAD) MAX_WIDTH_NUMPAD_DP else MAX_WIDTH_DP) * ctx.resources.displayMetrics.density
        val width = params.mOccupiedWidth
        if (width <= cap) return
        val side = ((width - cap) / 2).roundToInt()
        params.mLeftPadding = side
        params.mRightPadding = side
        params.mBaseWidth = width - 2 * side
    }

    /** Letter keyboards get a 5th row from the number row; number pads have 4, so drop that extra height. */
    fun applyHeight(params: KeyboardParams) {
        if (!params.mId.element.isNumberLayout || !Settings.getValues().mShowsNumberRow) return
        val f = 4f / 5f
        val shrink = (params.mBaseHeight * (1 - f)).roundToInt()
        params.mOccupiedHeight -= shrink
        params.mBaseHeight -= shrink
    }
}
