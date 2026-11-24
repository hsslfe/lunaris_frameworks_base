/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.volume.dialog.settings.ui.view

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.ImageButton
import com.android.systemui.volume.VolumeGradientColors

class VolumeSettingsButton(context: Context, attrs: AttributeSet) : ImageButton(context, attrs) {
    private val paint = Paint().apply { blendMode = BlendMode.SRC_IN }
    private val iconBounds = RectF()
    private var gradient: VolumeGradientColors? = null
    private var shaderTop = Float.NaN
    private var shaderBottom = Float.NaN

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        gradient = VolumeGradientColors.read(context)
        paint.shader = null
    }

    override fun onDraw(canvas: Canvas) {
        val colors = gradient
        val icon = drawable
        if (colors == null || icon == null || icon.bounds.isEmpty) {
            super.onDraw(canvas)
            return
        }

        iconBounds.set(icon.bounds)
        imageMatrix.mapRect(iconBounds)
        iconBounds.offset(paddingLeft.toFloat(), paddingTop.toFloat())
        if (paint.shader == null || shaderTop != iconBounds.top || shaderBottom != iconBounds.bottom) {
            shaderTop = iconBounds.top
            shaderBottom = iconBounds.bottom
            paint.shader = LinearGradient(
                0f, shaderTop, 0f, shaderBottom, colors.end, colors.start, Shader.TileMode.CLAMP
            )
        }
        val layer = canvas.saveLayer(iconBounds, null)
        super.onDraw(canvas)
        canvas.drawRect(iconBounds, paint)
        canvas.restoreToCount(layer)
    }
}
