/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settingslib.widget

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.R
import com.google.android.material.materialswitch.MaterialSwitch

/** MaterialSwitch with a live ON icon, retaining the normal Material switch behavior. */
class SwitchThumbIconSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.materialSwitchStyle,
) : MaterialSwitch(context, attrs, defStyleAttr) {
    init {
        SwitchThumbIconDrawable.bind(this)
    }
}
