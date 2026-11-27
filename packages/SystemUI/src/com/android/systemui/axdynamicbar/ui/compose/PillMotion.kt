package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.islandevents.model.IslandEvent

import kotlin.math.abs

internal class PillSwipeMotion(private val viewModel: AxDynamicBarChipViewModel) {
    var pull by mutableFloatStateOf(0f)
        private set
    private var fromId: String? = null
    private var toId: String? = null
    private var direction = 1

    fun drag(distance: Float, touchSlop: Float) {
        // Rubber-band response in either direction, independent of screen density.
        pull = distance / (abs(distance) + 3f * touchSlop.coerceAtLeast(1f))
    }

    fun release() {
        pull = 0f
    }

    fun cycle(previous: Boolean) {
        val activities = viewModel.interactor.uiState
        fromId = activities.value.topEvent?.id
        if (previous) viewModel.cyclePrev() else viewModel.cycleNext()
        toId = activities.value.topEvent?.id
        direction = if (previous) -1 else 1
    }

    fun direction(from: IslandEvent, to: IslandEvent, events: List<IslandEvent>): Int {
        if (from.id == fromId && to.id == toId) return direction
        val start = events.indexOfFirst { it.id == from.id }
        val end = events.indexOfFirst { it.id == to.id }
        return if (start >= 0 && end in 0 until start) -1 else 1
    }
}

@Composable
internal fun Modifier.pillPressMotion(pressed: Boolean): Modifier {
    val pressure = animateFloatAsState(
        if (pressed) 1f else 0f,
        animationSpec = if (pressed) spring(dampingRatio = 1f, stiffness = 1450f)
            else spring(dampingRatio = 0.68f, stiffness = 420f),
        label = "pill_press",
    )
    return graphicsLayer {
        val amount = pressure.value.coerceIn(-0.10f, 1f)
        scaleX = 1f - 0.042f * amount
        scaleY = 1f - 0.09f * amount
    }
}

@Composable
internal fun Modifier.pillSwipeMotion(swipe: PillSwipeMotion): Modifier {
    val target = swipe.pull
    val displacement = animateFloatAsState(
        target,
        animationSpec = if (target == 0f) spring(dampingRatio = 0.62f, stiffness = 330f)
            else spring(dampingRatio = 1f, stiffness = 2400f),
        visibilityThreshold = 0.002f,
        label = "pill_drag",
    )
    return graphicsLayer {
        val pull = displacement.value.coerceIn(-1f, 1f)
        scaleX = 1f - 0.045f * abs(pull)
        scaleY = 1f - 0.095f * abs(pull)
        // Translate within the space freed by compression to keep both ends visible.
        translationX = size.width * 0.018f * pull
    }
}

internal fun <T> AnimatedContentTransitionScope<T>.pillEventTransition(
    direction: Int,
): ContentTransform {
    val enter = fadeIn(tween(155)) +
        scaleIn(initialScale = 0.95f, animationSpec = spring(dampingRatio = 0.79f, stiffness = 485f)) +
        slideInHorizontally(spring(dampingRatio = 0.84f, stiffness = 520f)) { it / 8 * direction }
    val exit = fadeOut(tween(105)) + scaleOut(targetScale = 0.97f, animationSpec = tween(115)) +
        slideOutHorizontally(tween(115)) { -it / 8 * direction }
    return (enter togetherWith exit).using(SizeTransform(clip = true) { _, _ -> spring(dampingRatio = 1f, stiffness = 400f) })
}

internal fun pillPanelEnter(origin: TransformOrigin, edge: Int) =
    fadeIn(tween(185)) +
        scaleIn(spring(dampingRatio = 0.72f, stiffness = 390f), 0.87f, origin) +
        slideInVertically(spring(dampingRatio = 0.86f, stiffness = 450f)) { edge * it / 9 }

internal fun pillPanelExit(origin: TransformOrigin, edge: Int) =
    fadeOut(tween(155)) + scaleOut(tween(185), 0.96f, origin) +
        slideOutVertically(tween(185)) { edge * it / 12 }
