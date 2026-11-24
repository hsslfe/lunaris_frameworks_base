/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settingslib.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.android.settingslib.widget.theme.R
import com.google.android.material.materialswitch.MaterialSwitch

/** Replaces only the ON icon; the original drawable still supplies every default/OFF state. */
class SwitchThumbIconDrawable private constructor(
    private val stock: Drawable,
    private val store: SwitchThumbIconStore?,
    private val preview: Boolean = false,
    private val previewBitmap: Bitmap? = null,
) : Drawable(), Drawable.Callback {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var iconAlpha = 255
    private var tint: ColorStateList? = null

    init {
        stock.callback = this
        store?.addListener(this)
    }

    override fun draw(canvas: Canvas) {
        val checked = preview || state.contains(android.R.attr.state_checked)
        val icon = if (preview) previewBitmap else store?.bitmap
        if (checked && icon != null && !icon.isRecycled) {
            val tintAlpha = tint?.let { Color.alpha(it.getColorForState(state, it.defaultColor)) } ?: 255
            paint.alpha = iconAlpha * tintAlpha / 255
            canvas.drawBitmap(icon, null, bounds, paint)
        } else {
            stock.draw(canvas)
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        stock.bounds = bounds
    }

    override fun onStateChange(state: IntArray): Boolean {
        stock.state = state
        invalidateSelf()
        return true
    }

    override fun isStateful(): Boolean = true

    override fun getIntrinsicWidth(): Int = stock.intrinsicWidth

    override fun getIntrinsicHeight(): Int = stock.intrinsicHeight

    override fun setAlpha(alpha: Int) {
        iconAlpha = alpha
        stock.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = iconAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        stock.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        stock.setTintList(tint)
        invalidateSelf()
    }

    override fun setTintMode(tintMode: PorterDuff.Mode?) {
        stock.setTintMode(tintMode)
    }

    override fun onLayoutDirectionChanged(layoutDirection: Int): Boolean =
        stock.setLayoutDirection(layoutDirection)

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun invalidateDrawable(who: Drawable) = invalidateSelf()

    override fun scheduleDrawable(who: Drawable, what: Runnable, whenMillis: Long) =
        scheduleSelf(what, whenMillis)

    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

    companion object {
        @JvmStatic
        fun create(context: Context, stock: Drawable? = null): SwitchThumbIconDrawable =
            SwitchThumbIconDrawable(
                stock?.mutate() ?: requireNotNull(context.getDrawable(
                    R.drawable.settingslib_expressive_switch_thumb_icon)).mutate(),
                SwitchThumbIconStore.get(context),
            )

        /** A fixed ON preview, deliberately independent of the saved system-wide choice. */
        @JvmStatic
        fun createPreview(context: Context, bitmap: Bitmap?): SwitchThumbIconDrawable =
            SwitchThumbIconDrawable(
                requireNotNull(context.getDrawable(R.drawable.settingslib_expressive_icon_check)).mutate(),
                null,
                preview = true,
                previewBitmap = bitmap,
            )

        /** Attach without replacing any click, checked-change or preference persistence logic. */
        @JvmStatic
        fun bind(view: View) {
            if (view is MaterialSwitch) {
                if (view.thumbIconDrawable !is SwitchThumbIconDrawable) {
                    view.thumbIconDrawable = create(view.context, view.thumbIconDrawable)
                }
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) bind(view.getChildAt(i))
            }
        }
    }
}
