/*
 * Copyright (C) 2025 The AxionAOSP Project
 *           (C) 2024-2026 Lunaris AOSP
 *           (C) 2026 crDroid Android Project
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
package com.android.systemui.pulse

import android.content.Context
import android.graphics.PixelFormat
import android.media.session.PlaybackState
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.media.MediaSessionManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.ScrimUtils
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@SysUISingleton
class PulseViewController @Inject constructor(
    private val context: Context,
    private val windowManager: WindowManager,
    private val keyguardStateController: KeyguardStateController,
    private val statusBarStateController: StatusBarStateController,
    private val wakefulnessLifecycle: WakefulnessLifecycle
) : PulseAudioDataProcessor.DataListener,
    MediaSessionManager.MediaDataListener,
    ScrimUtils.ScrimEventListener {

    private val mainScope = MainScope()
    private var listenersRegistered = false
    private var destroyed = false

    private var isMediaPlaying = false
    private var bouncerShowing = false
    private var keyguardGoingAway = false
    private var keyguardFadingAway = false
    private val bouncerShowingOrKeyguardDismissing: Boolean
        get() = bouncerShowing || keyguardGoingAway || keyguardFadingAway ||
            keyguardStateController.isPrimaryBouncerShowing ||
            keyguardStateController.isKeyguardGoingAway ||
            keyguardStateController.isKeyguardFadingAway
    private var keyguardShowing = false
    private var isDozing = false
    private var isPulsing = false
    private var isScreenOff = false

    private val settingsRepository: PulseSettingsRepository =
        PulseSettingsRepository(context)

    private val view: PulseView =
        PulseView(context)

    // Keep the lockscreen view attached: detaching a PulseView cancels its engine.
    private var unlockedView: PulseView? = null
    private var mediaColor: Int? = null
    private var mediaTrack: Pair<String, String>? = null
    private var unlockedCaptureRefreshJob: Job? = null
    private var unlockedCaptureMonitorJob: Job? = null
    private var lastUnlockedDataUptime = 0L
    private var lastUnlockedCaptureRestartUptime = 0L
    private var unlockedCaptureRetryDelayMs = UNLOCKED_CAPTURE_RETRY_DELAY_MS

    private val audioProcessor: PulseAudioDataProcessor =
        PulseAudioDataProcessor(context).apply {
            setDataListener(this@PulseViewController)
        }

    private val bassHaptics: PulseBassHaptics =
        PulseBassHaptics(context)

    val pulseEnabled: Boolean
        get() = settingsRepository.isPulseEnabled() && !settingsRepository.isPulseMediaEnabled()

    private val isCollapsed: Boolean
        get() = ScrimUtils.get().isPanelFullyCollapsed()

    private val hapticsMode: Int
        get() = settingsRepository.getPulseHapticsMode()

    private val captureMode: PulseAudioDataProcessor.CaptureMode
        get() = settingsRepository.getCaptureMode()

    var pulseRunning: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            updatePulseDisplay(value)
        }

    private var showDelayJob: Job? = null
    private var hideDelayJob: Job? = null

    private val PULSE_SHOW_DELAY_MS = 300L
    private val PULSE_HIDE_DELAY_MS = 100L
    private val PULSE_FADE_IN_DURATION_MS = 200L
    private val PULSE_FADE_OUT_DURATION_MS = 150L
    private val settingsChangedListener: () -> Unit = { onSettingsChanged() }

    private val keyguardCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardShowingChanged() {
            this@PulseViewController.onKeyguardShowingChanged(keyguardStateController.isShowing)
        }

        override fun onKeyguardFadingAwayChanged() {
            this@PulseViewController.onKeyguardFadingAwayChanged(
                keyguardStateController.isKeyguardFadingAway)
        }

        override fun onKeyguardGoingAwayChanged() {
            this@PulseViewController.onKeyguardGoingAwayChanged(
                keyguardStateController.isKeyguardGoingAway)
        }

        override fun onPrimaryBouncerShowingChanged() {
            this@PulseViewController.onPrimaryBouncerShowingChanged(
                keyguardStateController.isPrimaryBouncerShowing)
        }
    }

    private val statusBarCallback = object : StatusBarStateController.StateListener {
        override fun onStateChanged(newState: Int) = updateState()

        override fun onDozingChanged(isDozing: Boolean) {
            this@PulseViewController.onDozingChanged(isDozing)
        }

        override fun onPulsingChanged(pulsing: Boolean) {
            this@PulseViewController.setPulsing(pulsing)
        }

        override fun onExpandedChanged(isExpanded: Boolean) = updateState()

        override fun onDreamingChanged(isDreaming: Boolean) = updateState()
    }

    private val wakefulnessObserver = object : WakefulnessLifecycle.Observer {
        override fun onStartedGoingToSleep() {
            mainScope.launch {
                isScreenOff = true
                updateState()
            }
        }

        override fun onStartedWakingUp() {
            this@PulseViewController.onStartedWakingUp()
        }

        override fun onFinishedWakingUp() = updateState()
    }

    init {
        INSTANCE = this

        view.initialize(settingsRepository)
        // Observe user switches even while Pulse is disabled for the current user.
        ScrimUtils.get().addListener(this)
        settingsRepository.addOnSettingsChangedListener(settingsChangedListener)
        onSettingsChanged()
    }

    fun getPulseView(): PulseView = view

    private fun shouldShowUnlockedPulse(): Boolean =
        settingsRepository.isPulseShowWhenUnlocked() &&
            !keyguardShowing && !keyguardStateController.isShowing &&
            !isDozing && !isPulsing && !isScreenOff &&
            wakefulnessLifecycle.wakefulness == WakefulnessLifecycle.WAKEFULNESS_AWAKE &&
            !statusBarStateController.isDozing && !statusBarStateController.isPulsing &&
            !statusBarStateController.isDreaming &&
            statusBarStateController.state == StatusBarState.SHADE &&
            !statusBarStateController.isExpanded

    private fun shouldShowPulse(): Boolean =
        !destroyed && pulseEnabled && isMediaPlaying &&
            !bouncerShowingOrKeyguardDismissing && isCollapsed &&
            (((keyguardShowing || keyguardStateController.isShowing) &&
                !isDozing && !isPulsing && !isScreenOff) ||
                ((isDozing || isPulsing) && settingsRepository.isPulseShowOnAmbient()) ||
                shouldShowUnlockedPulse())

    private fun updateState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainScope.launch { updateState() }
            return
        }
        if (destroyed) return

        showDelayJob?.cancel()
        hideDelayJob?.cancel()
        val shouldShow = shouldShowPulse()

        // An unlocked window must not linger over keyguard, the shade, or a sleeping screen.
        if (unlockedView != null && (!shouldShow || !shouldShowUnlockedPulse())) {
            hideUnlockedView()
            if (!shouldShow) {
                pulseRunning = false
                return
            }
        }

        // Apply the same settling delay when moving from lockscreen/AOD to an unlocked window.
        if (pulseRunning && unlockedView == null && shouldShowUnlockedPulse()) {
            pulseRunning = false
        }

        if (shouldShow && !pulseRunning) {
            showDelayJob = mainScope.launch {
                delay(PULSE_SHOW_DELAY_MS)
                if (shouldShowPulse()) pulseRunning = true
            }
        } else if (!shouldShow && pulseRunning) {
            hideDelayJob = mainScope.launch {
                delay(PULSE_HIDE_DELAY_MS)
                if (!shouldShowPulse()) pulseRunning = false
            }
        } else if (shouldShow && pulseRunning) {
            // The destination can change while playback and pulseRunning remain unchanged.
            updatePulseDisplay(true)
        }
    }

    private fun setListening(enabled: Boolean) {
        if (enabled == listenersRegistered) return
        listenersRegistered = enabled
        if (enabled) {
            keyguardShowing = keyguardStateController.isShowing || ScrimUtils.get().isKeyguardShowing()
            bouncerShowing = keyguardStateController.isPrimaryBouncerShowing
            keyguardGoingAway = keyguardStateController.isKeyguardGoingAway
            keyguardFadingAway = keyguardStateController.isKeyguardFadingAway
            isDozing = statusBarStateController.isDozing
            isPulsing = statusBarStateController.isPulsing
            isScreenOff = wakefulnessLifecycle.wakefulness == WakefulnessLifecycle.WAKEFULNESS_ASLEEP ||
                wakefulnessLifecycle.wakefulness == WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP
            isMediaPlaying = MediaSessionManager.get().isMediaPlaying
            keyguardStateController.addCallback(keyguardCallback)
            statusBarStateController.addCallback(statusBarCallback)
            wakefulnessLifecycle.addObserver(wakefulnessObserver)
            MediaSessionManager.get().addListener(this)
            updateViewElevation()
        } else {
            MediaSessionManager.get().removeListener(this)
            wakefulnessLifecycle.removeObserver(wakefulnessObserver)
            statusBarStateController.removeCallback(statusBarCallback)
            keyguardStateController.removeCallback(keyguardCallback)
            isMediaPlaying = false
        }
    }

    private fun onSettingsChanged() {
        mainScope.launch {
            if (destroyed) return@launch
            audioProcessor.captureMode = captureMode
            view.onCaptureModeChanged(audioProcessor.captureMode)
            unlockedView?.onCaptureModeChanged(audioProcessor.captureMode)
            setListening(pulseEnabled)
            if (!pulseEnabled) stopPulseImmediately()
            updateState()
            updatePulseDisplay(pulseRunning)
        }
    }

    private fun updatePulseDisplay(show: Boolean) {
        mainScope.launch {
            val visible = show && shouldShowPulse()
            if (visible && shouldShowUnlockedPulse()) {
                view.setVisibility(false, animate = false)
                if (!showUnlockedView()) {
                    pulseRunning = false
                    return@launch
                }
            } else {
                hideUnlockedView()
                view.setVisibility(visible, animate = true, durationMs =
                    if (visible) PULSE_FADE_IN_DURATION_MS else PULSE_FADE_OUT_DURATION_MS)
            }

            if (visible || (!destroyed && pulseEnabled && hapticsMode > 1)) {
                audioProcessor.startCapture()
            } else {
                audioProcessor.stopCapture()
                bassHaptics.reset()
            }
        }
    }

    @Suppress("DEPRECATION") // TYPE_SYSTEM_OVERLAY is intentional for this internal system window.
    private fun showUnlockedView(): Boolean {
        if (unlockedView != null) return true
        val pulseView = PulseView(context).apply {
            setVisibility(false, animate = false)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        pulseView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            private var attachedBefore = false

            override fun onViewAttachedToWindow(v: View) {
                if (attachedBefore && unlockedView === pulseView && !destroyed) {
                    // A window reattachment must not reuse the engine cancelled on detach.
                    pulseView.initialize(settingsRepository)
                    pulseView.onCaptureModeChanged(audioProcessor.captureMode)
                    mediaColor?.let { pulseView.onMediaColorsChanged(it) }
                    scheduleUnlockedCaptureRefresh()
                }
                attachedBefore = true
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })
        val params = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            // Above apps, but below navigation buttons and the gesture handle.
            LayoutParams.TYPE_SYSTEM_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or
                LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            title = "UnlockedPulse"
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            softInputMode = LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setTrustedOverlay()
        }
        try {
            windowManager.addView(pulseView, params)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to add unlocked Pulse window", e)
            return false
        }
        // Initialize only after a successful attachment; recreate after every removal.
        unlockedView = pulseView
        pulseView.initialize(settingsRepository)
        pulseView.onCaptureModeChanged(audioProcessor.captureMode)
        mediaColor?.let { pulseView.onMediaColorsChanged(it) }
        pulseView.setVisibility(true, animate = true, durationMs = PULSE_FADE_IN_DURATION_MS)
        startUnlockedCaptureMonitor(pulseView)
        return true
    }

    private fun hideUnlockedView() {
        unlockedCaptureRefreshJob?.cancel()
        unlockedCaptureRefreshJob = null
        unlockedCaptureMonitorJob?.cancel()
        unlockedCaptureMonitorJob = null
        val pulseView = unlockedView ?: return
        unlockedView = null
        pulseView.setVisibility(false, animate = false)
        try {
            windowManager.removeViewImmediate(pulseView)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unlocked Pulse window was already removed", e)
        }
    }

    private fun canRefreshUnlockedCapture(): Boolean =
        unlockedView != null && pulseRunning && shouldShowPulse() && shouldShowUnlockedPulse()

    private fun scheduleUnlockedCaptureRefresh() {
        if (!canRefreshUnlockedCapture()) return
        unlockedCaptureRefreshJob?.cancel()
        unlockedCaptureRefreshJob = mainScope.launch {
            // Metadata/album color can arrive before the next audio session is ready.
            delay(UNLOCKED_CAPTURE_REFRESH_DELAY_MS)
            if (canRefreshUnlockedCapture()) {
                unlockedCaptureRetryDelayMs = UNLOCKED_CAPTURE_RETRY_DELAY_MS
                restartUnlockedCapture()
            }
        }
    }

    private fun restartUnlockedCapture() {
        if (!canRefreshUnlockedCapture()) return
        // startCapture alone is a no-op when a stale Visualizer still reports itself active.
        audioProcessor.stopCapture()
        audioProcessor.startCapture()
        lastUnlockedCaptureRestartUptime = SystemClock.uptimeMillis()
        lastUnlockedDataUptime = lastUnlockedCaptureRestartUptime
    }

    private fun startUnlockedCaptureMonitor(pulseView: PulseView) {
        unlockedCaptureMonitorJob?.cancel()
        lastUnlockedDataUptime = SystemClock.uptimeMillis()
        lastUnlockedCaptureRestartUptime = lastUnlockedDataUptime
        unlockedCaptureRetryDelayMs = UNLOCKED_CAPTURE_RETRY_DELAY_MS
        unlockedCaptureMonitorJob = mainScope.launch {
            while (unlockedView === pulseView && canRefreshUnlockedCapture()) {
                delay(UNLOCKED_CAPTURE_CHECK_INTERVAL_MS)
                if (unlockedView !== pulseView || !canRefreshUnlockedCapture()) break
                if (!pulseView.isAttachedToWindow) {
                    stopPulseImmediately()
                    updateState()
                    break
                }
                val now = SystemClock.uptimeMillis()
                if (unlockedCaptureRefreshJob?.isActive != true &&
                    now - lastUnlockedDataUptime >= UNLOCKED_CAPTURE_STALL_TIMEOUT_MS &&
                    now - lastUnlockedCaptureRestartUptime >= unlockedCaptureRetryDelayMs) {
                    restartUnlockedCapture()
                    // Unsupported/offloaded streams must not cause rapid capture retries.
                    unlockedCaptureRetryDelayMs = (unlockedCaptureRetryDelayMs * 2)
                        .coerceAtMost(UNLOCKED_CAPTURE_MAX_RETRY_DELAY_MS)
                }
            }
        }
    }

    private fun stopPulseImmediately() {
        showDelayJob?.cancel()
        hideDelayJob?.cancel()
        pulseRunning = false
        hideUnlockedView()
    }

    override fun onDataUpdate(data: PulseData) {
        mainScope.launch {
            if (destroyed) return@launch
            if (unlockedView != null && data.isDataValid) {
                lastUnlockedDataUptime = SystemClock.uptimeMillis()
                unlockedCaptureRetryDelayMs = UNLOCKED_CAPTURE_RETRY_DELAY_MS
            }
            if (pulseEnabled && hapticsMode > 0 && (pulseRunning || hapticsMode > 1) &&
                captureMode == PulseAudioDataProcessor.CaptureMode.FFT) {
                bassHaptics.process(data.fftBytes)
            }
            if (pulseRunning) (unlockedView ?: view).updateVisualizerData(data)
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        mainScope.launch {
            isMediaPlaying = state == PlaybackState.STATE_PLAYING
            updateState()
        }
    }

    override fun onMediaColorsChanged(color: Int) {
        mainScope.launch {
            val changed = mediaColor != color
            mediaColor = color
            view.onMediaColorsChanged(color)
            unlockedView?.onMediaColorsChanged(color)
            if (changed) scheduleUnlockedCaptureRefresh()
        }
    }

    override fun onMetadataChanged(track: String, artist: String) {
        mainScope.launch {
            val nextTrack = track to artist
            if (mediaTrack != nextTrack) {
                mediaTrack = nextTrack
                scheduleUnlockedCaptureRefresh()
            }
        }
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        mainScope.launch {
            keyguardShowing = showing
            updateState()
        }
    }

    override fun onDozingChanged(dozing: Boolean) {
        mainScope.launch {
            isDozing = dozing
            updateViewElevation()
            updateState()
        }
    }

    override fun setPulsing(pulsing: Boolean) {
        mainScope.launch {
            isPulsing = pulsing
            updateViewElevation()
            updateState()
        }
    }

    private fun updateViewElevation() {
        val ambient = isDozing || isPulsing
        view.translationZ = if (ambient) 1f else 0f
    }

    override fun onExpandedFractionChanged(expandedFraction: Float) = updateState()

    override fun onBarStateChanged(state: Int) = updateState()

    override fun onQsVisibilityChanged(visible: Boolean) = updateState()

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        mainScope.launch {
            keyguardFadingAway = fadingAway
            if (fadingAway) stopPulseImmediately()
            updateState()
        }
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        mainScope.launch {
            keyguardGoingAway = goingAway
            if (goingAway) stopPulseImmediately()
            updateState()
        }
    }

    override fun onPrimaryBouncerShowingChanged(showing: Boolean) {
        mainScope.launch {
            bouncerShowing = showing
            updateState()
        }
    }

    override fun onScreenTurnedOff() {
        mainScope.launch {
            isScreenOff = true
            stopPulseImmediately()
            updateState()
        }
    }

    override fun onStartedWakingUp() {
        mainScope.launch {
            isScreenOff = false
            updateState()
        }
    }

    override fun onUserChanged() {
        mainScope.launch {
            stopPulseImmediately()
            audioProcessor.stopCapture()
            mediaColor = null
            mediaTrack = null
            bassHaptics.reset()
            settingsRepository.invalidateCache()
        }
    }

    fun destroy() {
        destroyed = true
        stopPulseImmediately()
        view.setVisibility(false, animate = false)
        settingsRepository.removeOnSettingsChangedListener(settingsChangedListener)
        setListening(false)
        ScrimUtils.get().removeListener(this)
        audioProcessor.cleanup()
        bassHaptics.reset()
        mainScope.cancel()
        if (INSTANCE === this) {
            INSTANCE = null
        }
    }

    companion object {
        private const val TAG = "PulseViewController"
        private const val UNLOCKED_CAPTURE_REFRESH_DELAY_MS = 300L
        private const val UNLOCKED_CAPTURE_CHECK_INTERVAL_MS = 1000L
        private const val UNLOCKED_CAPTURE_STALL_TIMEOUT_MS = 2000L
        private const val UNLOCKED_CAPTURE_RETRY_DELAY_MS = 2000L
        private const val UNLOCKED_CAPTURE_MAX_RETRY_DELAY_MS = 30000L

        @Volatile
        private var INSTANCE: PulseViewController? = null

        @JvmStatic
        fun get(context: Context): PulseViewController {
            return INSTANCE ?: throw IllegalStateException(
                "PulseViewController not initialized"
            )
        }
    }
}
