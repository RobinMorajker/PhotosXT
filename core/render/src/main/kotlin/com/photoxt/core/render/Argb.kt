package com.photoxt.core.render

/** The channels of an ARGB colour or pixel, each 0 to 255. */
internal object Argb {
    const val MAX = 0xFF
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8

    fun alpha(color: Int): Int = color ushr ALPHA_SHIFT

    fun red(color: Int): Int = (color shr RED_SHIFT) and MAX

    fun green(color: Int): Int = (color shr GREEN_SHIFT) and MAX

    fun blue(color: Int): Int = color and MAX

    fun of(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl ALPHA_SHIFT) or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
}
