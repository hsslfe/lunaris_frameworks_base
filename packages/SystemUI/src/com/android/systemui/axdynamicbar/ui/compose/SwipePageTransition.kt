package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

// The expanded media surface can follow the same fade without a second animator.
internal val LocalSwipePageOpacity = compositionLocalOf<() -> Float> { { 1f } }

/** Swap a single content layer at zero opacity. Keep the surrounding surface in place. */
@Composable
internal fun <T> SwipePageTransition(
    target: T,
    pageKey: Any,
    travel: (T) -> Dp,
    direction: (T, T) -> Int,
    durationMillis: Int,
    enterTravel: (T) -> Dp = travel,
    fadeOutMillis: Int = durationMillis / 3,
    fadeInMillis: Int = durationMillis * 2 / 3,
    exitEasing: Easing = FastOutSlowInEasing,
    enterEasing: Easing = LinearOutSlowInEasing,
    retargetFromCurrent: Boolean = false,
    preserveExitDuration: Boolean = false,
    content: @Composable (T, Modifier) -> Unit,
) {
    val next = key(pageKey) { rememberUpdatedState(target) }
    var shown by remember { mutableStateOf(next) }
    var shownKey by remember { mutableStateOf(pageKey) }
    val opacity = remember { Animatable(1f) }
    var side by remember { mutableIntStateOf(1) }
    var distance by remember { mutableStateOf(travel(target)) }

    LaunchedEffect(next, durationMillis, fadeOutMillis, fadeInMillis) {
        if (durationMillis == 0) {
            shown = next
            shownKey = pageKey
            opacity.snapTo(1f)
            return@LaunchedEffect
        }
        if (retargetFromCurrent && shownKey == pageKey) {
            // Returning to the still-visible card must undo its fade, not swap it
            // out a second time just because its composition key was recreated.
            shown = next
        } else if (shown !== next) {
            val forward = direction(shown.value, next.value)
            // Keep the current offset on interruption. Reverse travel only while invisible.
            if (opacity.value == 1f) {
                side = -forward
                distance = travel(shown.value)
            }
            val remainingExit =
                if (retargetFromCurrent && !preserveExitDuration)
                    (fadeOutMillis * opacity.value).roundToInt().coerceAtLeast(0)
                else fadeOutMillis
            opacity.animateTo(0f, tween(remainingExit, easing = exitEasing))
            shown = next
            shownKey = pageKey
            side = forward
            distance = enterTravel(next.value)
        }
        val remainingEnter =
            if (retargetFromCurrent)
                (fadeInMillis * (1f - opacity.value)).roundToInt().coerceAtLeast(0)
            else fadeInMillis
        opacity.animateTo(1f, tween(remainingEnter, easing = enterEasing))
    }
    val pageOpacity = remember(opacity) { { opacity.value } }
    CompositionLocalProvider(LocalSwipePageOpacity provides pageOpacity) {
        content(shown.value, Modifier.graphicsLayer {
            alpha = opacity.value
            translationX = side * distance.toPx() * (1f - alpha)
        })
    }
}
