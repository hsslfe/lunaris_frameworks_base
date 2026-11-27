/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.ui.viewmodel

import android.graphics.Point
import android.util.MathUtils
import android.view.View.VISIBLE
import androidx.annotation.VisibleForTesting
import com.android.app.tracing.coroutines.flow.traceAs
import com.android.systemui.Flags
import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryBypassInteractor
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.PulseExpansionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OFF
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.shared.model.TransitionState.CANCELED
import com.android.systemui.keyguard.shared.model.TransitionState.FINISHED
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.StateToValue
import com.android.systemui.minmode.MinModeManager
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.ScreenPowerState
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.ui.viewmodel.NotificationShadeWindowModel
import com.android.systemui.statusbar.notification.domain.interactor.NotificationsKeyguardInteractor
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel
import com.android.systemui.statusbar.notification.promoted.domain.interactor.AODPromotedNotificationInteractor
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.util.kotlin.BooleanFlowOperators.anyOf
import com.android.systemui.util.kotlin.FlowDumperImpl
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.ui.AnimatableEvent
import com.android.systemui.util.ui.AnimatedValue
import com.android.systemui.util.ui.toAnimatedValueFlow
import com.android.systemui.util.ui.zip
import com.android.systemui.utils.coroutines.flow.flatMapLatestConflated
import java.util.Optional
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.round
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class KeyguardRootViewModel
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val deviceEntryBypassInteractor: DeviceEntryBypassInteractor,
    private val dozeParameters: DozeParameters,
    private val keyguardInteractor: KeyguardInteractor,
    communalInteractor: CommunalInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    powerInteractor: PowerInteractor,
    private val notificationsKeyguardInteractor: NotificationsKeyguardInteractor,
    private val pulseExpansionInteractor: PulseExpansionInteractor,
    notificationShadeWindowModel: NotificationShadeWindowModel,
    aodPromotedNotificationInteractor: AODPromotedNotificationInteractor,
    aodNotificationIconViewModel: NotificationIconContainerAlwaysOnDisplayViewModel,
    private val alternateBouncerToAodTransitionViewModel: AlternateBouncerToAodTransitionViewModel,
    private val alternateBouncerToGoneTransitionViewModel:
        AlternateBouncerToGoneTransitionViewModel,
    private val alternateBouncerToLockscreenTransitionViewModel:
        AlternateBouncerToLockscreenTransitionViewModel,
    private val alternateBouncerToOccludedTransitionViewModel:
        AlternateBouncerToOccludedTransitionViewModel,
    private val alternateBouncerToPrimaryBouncerTransitionViewModel:
        AlternateBouncerToPrimaryBouncerTransitionViewModel,
    private val aodToGoneTransitionViewModel: AodToGoneTransitionViewModel,
    private val aodToLockscreenTransitionViewModel: AodToLockscreenTransitionViewModel,
    private val aodToOccludedTransitionViewModel: AodToOccludedTransitionViewModel,
    private val aodToPrimaryBouncerTransitionViewModel: AodToPrimaryBouncerTransitionViewModel,
    private val aodToGlanceableHubTransitionViewModel: AodToGlanceableHubTransitionViewModel,
    private val dozingToDreamingTransitionViewModel: DozingToDreamingTransitionViewModel,
    private val dozingToGoneTransitionViewModel: DozingToGoneTransitionViewModel,
    private val dozingToLockscreenTransitionViewModel: DozingToLockscreenTransitionViewModel,
    private val dozingToOccludedTransitionViewModel: DozingToOccludedTransitionViewModel,
    private val dozingToPrimaryBouncerTransitionViewModel:
        DozingToPrimaryBouncerTransitionViewModel,
    private val dreamingToAodTransitionViewModel: DreamingToAodTransitionViewModel,
    private val dreamingToGoneTransitionViewModel: DreamingToGoneTransitionViewModel,
    private val dreamingToLockscreenTransitionViewModel: DreamingToLockscreenTransitionViewModel,
    private val glanceableHubToLockscreenTransitionViewModel:
        GlanceableHubToLockscreenTransitionViewModel,
    private val glanceableHubToAodTransitionViewModel: GlanceableHubToAodTransitionViewModel,
    private val goneToAodTransitionViewModel: GoneToAodTransitionViewModel,
    private val goneToDozingTransitionViewModel: GoneToDozingTransitionViewModel,
    private val goneToDreamingTransitionViewModel: GoneToDreamingTransitionViewModel,
    private val goneToLockscreenTransitionViewModel: GoneToLockscreenTransitionViewModel,
    private val lockscreenToAodTransitionViewModel: LockscreenToAodTransitionViewModel,
    private val lockscreenToDozingTransitionViewModel: LockscreenToDozingTransitionViewModel,
    private val lockscreenToDreamingTransitionViewModel: LockscreenToDreamingTransitionViewModel,
    private val lockscreenToGlanceableHubTransitionViewModel:
        LockscreenToGlanceableHubTransitionViewModel,
    private val lockscreenToGoneTransitionViewModel: LockscreenToGoneTransitionViewModel,
    private val lockscreenToOccludedTransitionViewModel: LockscreenToOccludedTransitionViewModel,
    private val lockscreenToPrimaryBouncerTransitionViewModel:
        LockscreenToPrimaryBouncerTransitionViewModel,
    private val occludedToAlternateBouncerTransitionViewModel:
        OccludedToAlternateBouncerTransitionViewModel,
    private val occludedToAodTransitionViewModel: OccludedToAodTransitionViewModel,
    private val occludedToDozingTransitionViewModel: OccludedToDozingTransitionViewModel,
    private val occludedToLockscreenTransitionViewModel: OccludedToLockscreenTransitionViewModel,
    private val occludedToPrimaryBouncerTransitionViewModel:
        OccludedToPrimaryBouncerTransitionViewModel,
    private val offToLockscreenTransitionViewModel: OffToLockscreenTransitionViewModel,
    private val primaryBouncerToAodTransitionViewModel: PrimaryBouncerToAodTransitionViewModel,
    private val primaryBouncerToGoneTransitionViewModel: PrimaryBouncerToGoneTransitionViewModel,
    private val primaryBouncerToLockscreenTransitionViewModel:
        PrimaryBouncerToLockscreenTransitionViewModel,
    private val primaryBouncerToDozingTransitionViewModel:
        PrimaryBouncerToDozingTransitionViewModel,
    private val alternateBouncerToDozingTransitionViewModel:
        AlternateBouncerToDozingTransitionViewModel,
    private val glanceableHubToDozingTransitionViewModel: GlanceableHubToDozingTransitionViewModel,
    private val screenOffAnimationController: ScreenOffAnimationController,
    private val aodBurnInViewModel: AodBurnInViewModel,
    shadeInteractor: ShadeInteractor,
    dumpManager: DumpManager,
    private val minModeManager: Optional<MinModeManager>,
) : FlowDumperImpl(dumpManager) {
    val burnInLayerVisibility: Flow<Int> =
        keyguardTransitionInteractor.startedKeyguardTransitionStep
            .filter { it.to == AOD || it.to == LOCKSCREEN }
            .map { VISIBLE }
            .dumpWhileCollecting("burnInLayerVisibility")

    val goneToAodTransition =
        keyguardTransitionInteractor
            .transition(
                edge = Edge.create(Scenes.Gone, AOD),
                edgeWithoutSceneContainer = Edge.create(GONE, AOD),
            )
            .dumpWhileCollecting("goneToAodTransition")

    private val goneToAodTransitionRunning: Flow<Boolean> =
        goneToAodTransition
            .map { it.transitionState == STARTED || it.transitionState == RUNNING }
            .onStart { emit(false) }
            .distinctUntilChanged()

    private val isOnOrGoingToLockscreen: Flow<Boolean> =
        combine(
                keyguardTransitionInteractor.transitionValue(LOCKSCREEN).map { it == 1f },
                keyguardTransitionInteractor.isInTransition(Edge.create(to = LOCKSCREEN)),
            ) { onLockscreen, transitioningToLockscreen ->
                onLockscreen || transitioningToLockscreen
            }
            .distinctUntilChanged()

    private val alphaOnShadeExpansion: Flow<Float> =
        combineTransform(
                keyguardTransitionInteractor.isInTransition(
                    edge = Edge.create(from = Overlays.Bouncer, to = LOCKSCREEN),
                    edgeWithoutSceneContainer = Edge.create(from = PRIMARY_BOUNCER, to = LOCKSCREEN),
                ),
                isOnOrGoingToLockscreen,
                shadeInteractor.qsExpansion,
                shadeInteractor.shadeExpansion,
            ) { disabledTransitionRunning, isOnOrGoingToLockscreen, qsExpansion, shadeExpansion ->
                // Fade out quickly as the shade expands
                if (isOnOrGoingToLockscreen && !disabledTransitionRunning) {
                    val alpha =
                        1f -
                            MathUtils.constrainedMap(
                                /* rangeMin = */ 0f,
                                /* rangeMax = */ 1f,
                                /* valueMin = */ 0f,
                                /* valueMax = */ 0.2f,
                                /* value = */ max(qsExpansion, shadeExpansion),
                            )
                    emit(alpha)
                }
            }
            .distinctUntilChanged()

    /**
     * Keyguard should not show in any of the following conditions:
     * 1. If fully transitioned into a hidden keyguard state
     * 2. If transitioning between hidden states
     * 3. If minmode activity is in foreground.
     */
    private val hideKeyguard: Flow<Boolean> =
        anyOf(
            if (minModeManager.isPresent) minModeManager.get().isMinModeInForegroundFlow
            else flowOf(false),
            notificationShadeWindowModel.isKeyguardOccluded,
            keyguardInteractor.lockStylesOccluding,
            communalInteractor.isIdleOnCommunal,
            keyguardTransitionInteractor
                .transitionValue(OFF)
                .map { it > 1f - offToLockscreenTransitionViewModel.alphaStartAt }
                .onStart { emit(false) },
            keyguardTransitionInteractor
                .transitionValue(content = Scenes.Gone, stateWithoutSceneContainer = GONE)
                .map { it == 1f }
                .onStart { emit(false) },
        )

    private data class LockscreenWakeInput(
        val step: TransitionStep,
        val awake: Boolean,
        val screen: ScreenPowerState,
        val aodAvailable: Boolean,
    )

    private data class LockscreenWakeReveal(
        val input: LockscreenWakeInput? = null,
        val armed: Boolean = false,
        val waitingForFreshWake: Boolean = false,
        val startProgress: Float? = null,
        val startAlpha: Float = 0f,
        val alpha: Float = 1f,
    )

    // Reduce power and keyguard events together. In particular, a reversed wake can
    // leave the previous DOZING -> LOCKSCREEN step visible in transitionState until
    // the next transition starts. Never use that old progress for a new wake.
    private val lockscreenWakeAlphaLimit: Flow<Float> =
        combine(
                keyguardTransitionInteractor.transitionState,
                powerInteractor.detailedWakefulness
                    .map { it.isAwake() }
                    .distinctUntilChanged(),
                powerInteractor.screenPowerState,
                keyguardInteractor.isAodAvailable,
            ) { step, awake, screen, aodAvailable ->
                LockscreenWakeInput(step, awake, screen, aodAvailable)
            }
            .runningFold(LockscreenWakeReveal()) { previous, input ->
                reduceLockscreenWakeReveal(previous, input)
            }
            .map { it.alpha }
            .distinctUntilChanged()

    private fun reduceLockscreenWakeReveal(
        previous: LockscreenWakeReveal,
        input: LockscreenWakeInput,
    ): LockscreenWakeReveal {
        val step = input.step
        val reset = LockscreenWakeReveal(input = input)
        if (input.aodAvailable) return reset

        val sleepTransition =
            (step.from == LOCKSCREEN || step.from == GONE) && step.to == DOZING
        val wakeTransition = step.from == DOZING && step.to == LOCKSCREEN
        val previousStep = previous.input?.step
        // Use one transition stream. Its STARTED event and first RUNNING
        // frame can be conflated, but a second, independently collected
        // STARTED stream must never re-anchor an already running reveal.
        val freshWake = wakeTransition && previousStep != step && (
            step.transitionState == STARTED ||
                previousStep?.from != DOZING || previousStep.to != LOCKSCREEN ||
                (step.transitionState == RUNNING &&
                    (previousStep.transitionState == CANCELED ||
                        previousStep.transitionState == FINISHED ||
                        step.value < previousStep.value))
            )
        val screenOff = input.screen == ScreenPowerState.SCREEN_OFF
        val turningOff = input.screen == ScreenPowerState.SCREEN_TURNING_OFF

        if (!input.awake || turningOff) {
            // Arm on the power event too: rapid reversal may conflate the sleep
            // transition's STARTED and FINISHED steps into the next wake step.
            val armed = previous.armed || sleepTransition ||
                step.to == LOCKSCREEN || step.to == GONE
            if (!armed) return reset

            val interrupted = previous.startProgress != null ||
                (previous.armed && previous.alpha < 1f)
            val wasSleeping = previous.input?.let {
                !it.awake || it.screen == ScreenPowerState.SCREEN_TURNING_OFF
            } == true
            return reset.copy(
                armed = true,
                waitingForFreshWake = wakeTransition && !freshWake &&
                    (!wasSleeping || previous.waitingForFreshWake),
                // Do not restart a half-revealed clock at full opacity on sleep.
                // Normal screen-off animations and doze pulses retain their alpha.
                alpha = when {
                    screenOff -> 0f
                    interrupted -> previous.alpha
                    else -> 1f
                },
            )
        }

        if (sleepTransition) {
            // Power reversed before keyguard started its next wake. If the
            // screen never went off, retain the existing opacity rather than
            // inserting an artificial black frame between the two transitions.
            return reset.copy(
                armed = true,
                alpha = if (screenOff) 0f else previous.alpha,
            )
        }
        if (!previous.armed) return reset

        if (wakeTransition) {
            if (previous.waitingForFreshWake && !freshWake) {
                // No new wake transition may be necessary when power reverses
                // before keyguard has left LOCKSCREEN. Release on a settled display,
                // not from a stale RUNNING or CANCELED step.
                if (input.screen == ScreenPowerState.SCREEN_ON &&
                    step.transitionState == FINISHED) return reset
                return reset.copy(
                    armed = true,
                    waitingForFreshWake = true,
                    alpha = if (screenOff) 0f else previous.alpha,
                )
            }
            if (step.transitionState == CANCELED) {
                return reset.copy(
                    armed = true,
                    waitingForFreshWake = true,
                    alpha = if (screenOff) 0f else previous.alpha,
                )
            }
            if (screenOff) {
                // Do not consume the reveal while the display is still unpowered.
                return reset.copy(armed = true, startProgress = step.value, alpha = 0f)
            }
            if (step.transitionState == FINISHED) return reset

            val reanchor = freshWake || previous.startProgress == null ||
                previous.input?.screen == ScreenPowerState.SCREEN_OFF
            val startProgress = if (reanchor) step.value else previous.startProgress!!
            val startAlpha = if (reanchor) previous.alpha else previous.startAlpha
            val progress =
                if (startProgress >= 1f) 0f
                else ((step.value - startProgress) / (1f - startProgress)).coerceIn(0f, 1f)
            val alpha = MathUtils.lerp(startAlpha, 1f, progress)
            return reset.copy(
                armed = true,
                startProgress = startProgress,
                startAlpha = startAlpha,
                // Never dim again because a stale or out-of-order progress
                // sample was delivered during the same wake.
                alpha = maxOf(previous.alpha, alpha),
            )
        }

        // A different destination owns its own visibility (unlock, bouncer, etc.).
        // If keyguard never left LOCKSCREEN, hold only until the display is on.
        if (step.to == LOCKSCREEN && step.transitionState == FINISHED &&
            input.screen != ScreenPowerState.SCREEN_ON) {
            return reset.copy(armed = true, alpha = 0f)
        }
        return reset
    }

    private val zoomOutFromGlanceableHub: Flow<Float> =
        if (!Flags.gestureBetweenHubAndLockscreenMotion()) {
                emptyFlow()
            } else {
                // Use flatMapLatestConflated so the animation flows aren't collected at all when
                // communal is not visible.
                communalInteractor.isCommunalVisible.flatMapLatestConflated { isCommunalVisible ->
                    if (!isCommunalVisible) {
                        // reset zoom out once we've exited the communal scene
                        flowOf(0f)
                    } else {
                        merge(
                                lockscreenToGlanceableHubTransitionViewModel.zoomOut,
                                glanceableHubToLockscreenTransitionViewModel.zoomOut,
                                aodToGlanceableHubTransitionViewModel.zoomOut,
                                glanceableHubToAodTransitionViewModel.zoomOut,
                            )
                            .map {
                                // rate limit the zoom out by 5% step to avoid jank
                                (round(it * 20) / 20f).coerceIn(0f, 1f)
                            }
                    }
                }
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("zoomOutFromGlanceableHub")

    private fun dozingToLockscreenAlpha(viewState: ViewStateAccessor) =
        alphaOnShadeExpansion
            .map { it < 1f }
            .distinctUntilChanged()
            .onStart { emit(false) }
            .flatMapLatest { isExpanding ->
                if (Flags.deferDozeTransitionOnShadeDrag() && isExpanding) {
                    // If shade is expanding, switch to a flow that never emits.
                    emptyFlow()
                } else {
                    dozingToLockscreenTransitionViewModel.lockscreenAlpha(viewState)
                }
            }

    /** Last point that the root view was tapped */
    val lastRootViewTapPosition: Flow<Point?> =
        keyguardInteractor.lastRootViewTapPosition.dumpWhileCollecting("lastRootViewTapPosition")

    /**
     * The keyguard root view can be clipped as the shade is pulled down, typically only for
     * non-split shade cases.
     */
    val topClippingBounds: Flow<Int?> =
        combine(keyguardInteractor.topClippingBounds, shadeInteractor.isShadeAnyExpanded) {
                topClippingBounds,
                isShadeAnyExpanded ->
                if (isShadeAnyExpanded) topClippingBounds else null
            }
            .dumpWhileCollecting("topClippingBounds")

    /**
     * Alpha for the non-authentication related views. While DOZING, the pulsing auth state may
     * request for non-authentication related views to hide. Therefore, use this flow to update the
     * opacity of non-auth views.
     */
    val nonAuthUIAlpha: Flow<Float> =
        merge(
            alternateBouncerToDozingTransitionViewModel.nonAuthUIAlpha,
            glanceableHubToDozingTransitionViewModel.nonAuthUIAlpha,
            goneToDozingTransitionViewModel.nonAuthUIAlpha,
            lockscreenToDozingTransitionViewModel.nonAuthUIAlpha,
            occludedToDozingTransitionViewModel.nonAuthUIAlpha,
            primaryBouncerToDozingTransitionViewModel.nonAuthUIAlpha,
            dozingToDreamingTransitionViewModel.nonAuthUIAlpha,
            dozingToGoneTransitionViewModel.nonAuthUIAlpha,
            dozingToLockscreenTransitionViewModel.nonAuthUIAlpha,
            dozingToOccludedTransitionViewModel.nonAuthUIAlpha,
            dozingToPrimaryBouncerTransitionViewModel.nonAuthUIAlpha,
        )

    /** An observable for the alpha level for the entire keyguard root view. */
    fun alpha(viewState: ViewStateAccessor): Flow<Float> {
        return combine(
                hideKeyguard,
                lockscreenWakeAlphaLimit,
                // The transitions are mutually exclusive, so they are safe to merge to get the last
                // value emitted by any of them. Do not add flows that cannot make this guarantee.
                merge(
                        alphaOnShadeExpansion,
                        keyguardInteractor.dismissAlpha,
                        alternateBouncerToAodTransitionViewModel.lockscreenAlpha(viewState),
                        alternateBouncerToDozingTransitionViewModel.lockscreenAlpha,
                        alternateBouncerToGoneTransitionViewModel.lockscreenAlpha(viewState),
                        alternateBouncerToLockscreenTransitionViewModel.lockscreenAlpha(viewState),
                        alternateBouncerToPrimaryBouncerTransitionViewModel.lockscreenAlpha,
                        alternateBouncerToOccludedTransitionViewModel.lockscreenAlpha,
                        aodToGoneTransitionViewModel.lockscreenAlpha(viewState),
                        aodToLockscreenTransitionViewModel.lockscreenAlpha(viewState),
                        aodToOccludedTransitionViewModel.lockscreenAlpha(viewState),
                        aodToPrimaryBouncerTransitionViewModel.lockscreenAlpha,
                        aodToGlanceableHubTransitionViewModel.lockscreenAlpha(viewState),
                        dozingToDreamingTransitionViewModel.lockscreenAlpha,
                        dozingToGoneTransitionViewModel.lockscreenAlpha(viewState),
                        dozingToLockscreenAlpha(viewState),
                        dozingToOccludedTransitionViewModel.lockscreenAlpha(viewState),
                        dozingToPrimaryBouncerTransitionViewModel.lockscreenAlpha,
                        dreamingToAodTransitionViewModel.lockscreenAlpha,
                        dreamingToGoneTransitionViewModel.lockscreenAlpha,
                        dreamingToLockscreenTransitionViewModel.lockscreenAlpha,
                        glanceableHubToDozingTransitionViewModel.lockscreenAlpha,
                        glanceableHubToLockscreenTransitionViewModel.keyguardAlpha,
                        glanceableHubToAodTransitionViewModel.lockscreenAlpha,
                        goneToAodTransitionViewModel.enterFromTopAnimationAlpha,
                        goneToDozingTransitionViewModel.lockscreenAlpha,
                        goneToDreamingTransitionViewModel.lockscreenAlpha(),
                        goneToLockscreenTransitionViewModel.lockscreenAlpha,
                        lockscreenToAodTransitionViewModel.lockscreenAlpha(viewState),
                        lockscreenToAodTransitionViewModel.lockscreenAlphaOnFold,
                        lockscreenToDozingTransitionViewModel.lockscreenAlpha,
                        lockscreenToDreamingTransitionViewModel.lockscreenAlpha,
                        lockscreenToGlanceableHubTransitionViewModel.keyguardAlpha,
                        lockscreenToGoneTransitionViewModel.lockscreenAlpha(viewState),
                        lockscreenToOccludedTransitionViewModel.lockscreenAlpha,
                        lockscreenToPrimaryBouncerTransitionViewModel.lockscreenAlpha,
                        occludedToAlternateBouncerTransitionViewModel.lockscreenAlpha,
                        occludedToAodTransitionViewModel.lockscreenAlpha,
                        occludedToDozingTransitionViewModel.lockscreenAlpha,
                        occludedToLockscreenTransitionViewModel.lockscreenAlpha,
                        occludedToPrimaryBouncerTransitionViewModel.lockscreenAlpha,
                        offToLockscreenTransitionViewModel.lockscreenAlpha,
                        primaryBouncerToAodTransitionViewModel.lockscreenAlpha,
                        primaryBouncerToDozingTransitionViewModel.lockscreenAlpha,
                        primaryBouncerToGoneTransitionViewModel.lockscreenAlpha,
                        primaryBouncerToLockscreenTransitionViewModel.lockscreenAlpha(viewState),
                    )
                    .onStart { emit(0f) },
            ) { hideKeyguard, wakeAlphaLimit, alpha ->
                if (hideKeyguard) {
                    0f
                } else {
                    minOf(alpha, wakeAlphaLimit)
                }
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("alpha")
    }

    val scaleFromZoomOut: Flow<Float> =
        merge(keyguardInteractor.zoomOut, zoomOutFromGlanceableHub)
            .map { 1 - it * PUSHBACK_SCALE_FOR_LOCKSCREEN }
            .dumpWhileCollecting("scaleFromZoomOut")

    val translationY: Flow<Float> =
        aodBurnInViewModel.movement
            .map { it.translationY.toFloat() }
            .dumpWhileCollecting("translationY")

    val translationX: Flow<StateToValue> =
        merge(
                aodBurnInViewModel.movement.map {
                    StateToValue(to = AOD, value = it.translationX.toFloat())
                },
                lockscreenToGlanceableHubTransitionViewModel.keyguardTranslationX,
                glanceableHubToLockscreenTransitionViewModel.keyguardTranslationX,
            )
            .dumpWhileCollecting("translationX")

    fun updateBurnInParams(params: BurnInParameters) {
        aodBurnInViewModel.updateBurnInParams(params)
    }

    val scale: Flow<BurnInScaleViewModel> =
        aodBurnInViewModel.movement
            .map { BurnInScaleViewModel(scale = it.scale, scaleClockOnly = it.scaleClockOnly) }
            .dumpWhileCollecting("scale")

    val isAodPromotedNotifVisible: StateFlow<AnimatedValue<Boolean>> =
        combine(
                keyguardTransitionInteractor
                    .transitionValue(AOD)
                    .map { it == 1f }
                    .distinctUntilChanged(),
                areNotifsFullyHiddenAnimated(),
                isPulseExpandingAnimated(),
                aodPromotedNotificationInteractor.isPresent,
            ) { isOnAOD, notifsFullyHiddenAnimated, pulseExpandingAnimated, haveAodPromotedNotif ->
                zip(notifsFullyHiddenAnimated, pulseExpandingAnimated) {
                    notifsFullyHidden,
                    pulseExpanding ->
                    isOnAOD && notifsFullyHidden && !pulseExpanding && haveAodPromotedNotif
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = AnimatedValue.NotAnimating(false),
            )
            .dumpValue("isAodPromotedNotifVisible")
            .traceAs("KeyguardRootVM#aodRONVisible")

    /** Is the notification icon container visible? */
    val isNotifIconContainerVisible: StateFlow<AnimatedValue<Boolean>> =
        combine(
                goneToAodTransitionRunning,
                keyguardTransitionInteractor.isFinishedIn(
                    content = Scenes.Gone,
                    stateWithoutSceneContainer = GONE,
                ),
                areNotifsFullyHiddenAnimated(),
                isPulseExpandingAnimated(),
                aodNotificationIconViewModel.icons.map { it.visibleIcons.isNotEmpty() },
            ) { flows ->
                val goneToAodTransitionRunning = flows[0] as Boolean
                val isOnGone = flows[1] as Boolean
                val notifsFullyHidden = flows[2] as AnimatedValue<Boolean>
                val pulseExpanding = flows[3] as AnimatedValue<Boolean>
                val hasAodIcons = flows[4] as Boolean

                when {
                    // Hide the AOD icons if we're not in the KEYGUARD state unless the screen off
                    // animation is playing, in which case we want them to be visible if we're
                    // animating in the AOD UI and will be switching to KEYGUARD shortly.
                    goneToAodTransitionRunning ||
                        (isOnGone && !screenOffAnimationController.shouldShowAodIconsWhenShade()) ->
                        AnimatedValue.NotAnimating(false)
                    else ->
                        zip(notifsFullyHidden, pulseExpanding) {
                            areNotifsFullyHidden,
                            isPulseExpanding ->
                            when {
                                // If there are no notification icons to show, then it can be hidden
                                !hasAodIcons -> false
                                // If we are pulsing (and not bypassing), then we are hidden
                                isPulseExpanding -> false
                                // If notifs are fully gone, then we're visible
                                areNotifsFullyHidden -> true
                                // Otherwise, we're hidden
                                else -> false
                            }
                        }
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = AnimatedValue.NotAnimating(false),
            )
            .dumpValue("isNotifIconContainerVisible")

    fun onNotificationContainerBoundsChanged(top: Float, bottom: Float, animate: Boolean = false) {
        keyguardInteractor.setNotificationContainerBounds(
            NotificationContainerBounds(top = top, bottom = bottom, isAnimated = animate)
        )
    }

    /** Is there an expanded pulse, are we animating in response? */
    private fun isPulseExpandingAnimated(): Flow<AnimatedValue<Boolean>> {
        return pulseExpansionInteractor.isPulseExpanding
            .pairwise(initialValue = null)
            // If pulsing changes, start animating, unless it's the first emission
            .map { (prev, expanding) -> AnimatableEvent(expanding, startAnimating = prev != null) }
            .toAnimatedValueFlow()
    }

    /** Are notifications completely hidden from view, are we animating in response? */
    private fun areNotifsFullyHiddenAnimated(): Flow<AnimatedValue<Boolean>> {
        return notificationsKeyguardInteractor.areNotificationsFullyHidden
            .pairwise(initialValue = null)
            .sample(deviceEntryBypassInteractor.isBypassEnabled) {
                (prev, fullyHidden),
                bypassEnabled ->
                val animate =
                    when {
                        // Don't animate for the first value
                        prev == null -> false
                        // Always animate if bypass is enabled.
                        bypassEnabled -> true
                        // If we're not bypassing and we're not going to AOD, then we're not
                        // animating.
                        !dozeParameters.alwaysOn -> false
                        // Don't animate when going to AOD if the display needs blanking.
                        dozeParameters.displayNeedsBlanking -> false
                        else -> true
                    }
                AnimatableEvent(fullyHidden, animate)
            }
            .toAnimatedValueFlow()
    }

    fun setRootViewLastTapPosition(point: Point) {
        keyguardInteractor.setLastRootViewTapPosition(point)
    }

    companion object {
        private const val TAG = "KeyguardRootViewModel"
        @VisibleForTesting const val PUSHBACK_SCALE_FOR_LOCKSCREEN = 0.05f
    }
}
