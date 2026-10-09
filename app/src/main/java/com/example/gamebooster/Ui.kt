package com.example.gamebooster

import android.content.Context
import android.graphics.drawable.GradientDrawable

object Ui {
    val BG = 0xFF0D0F14.toInt()
    val CARD = 0xFF181B22.toInt()
    val CARD2 = 0xFF222733.toInt()
    val ACCENT = 0xFFFF5A1F.toInt()
    val ACCENT2 = 0xFFFF9A3D.toInt()
    val TEXT = 0xFFFFFFFF.toInt()
    val MUTED = 0xFF8B93A5.toInt()
    val GOOD = 0xFF3DDC84.toInt()
    val BAD = 0xFFFF4D4D.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(ctx, strokeDp), strokeColor)
        }

    fun gradient(ctx: Context, radiusDp: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT, ACCENT2)).apply {
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
}
