package com.android.systemui.axdynamicbar.domain

import android.app.Notification
import android.media.AudioManager
import android.net.Uri
import android.os.UserHandle
import com.android.systemui.axdynamicbar.data.IslandEventRepository
import com.android.systemui.islandevents.model.IslandEvent
import com.android.systemui.axdynamicbar.model.IslandState
import com.android.systemui.axdynamicbar.model.IslandUiState
import com.android.systemui.islandevents.model.RecordingState
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.media.controls.shared.MediaSeekBarStyle
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.systemui.util.settings.GlobalSettings
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@SysUISingleton
class AxDynamicBarInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val repository: IslandEventRepository,
    val settings: AxDynamicBarSettings,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    val sliderHapticsViewModelFactory: SliderHapticsViewModel.Factory,
    private val activityStarter: ActivityStarter,
    private val indicationController: KeyguardIndicationController,
    private val shadeInteractor: ShadeInteractor,
    private val shadeRepository: ShadeRepository,
    private val zenModeController: ZenModeController,
    private val globalSettings: GlobalSettings,
    private val audioManager: AudioManager,
    private val powerInteractor: PowerInteractor,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val notifCollection: CommonNotifCollection,
    private val keyguardNotificationVisibilityProvider: KeyguardNotificationVisibilityProvider,
) : IslandActions {
    private val _uiState = MutableStateFlow(IslandUiState())
    val uiState: StateFlow<IslandUiState> = _uiState.asStateFlow()
    override val chargerReadings = repository.system.chargerReadings

    private val _lockscreenPrivacyTick = MutableStateFlow(0)

    private val autoDismissJobs = ConcurrentHashMap<String, Job>()
    @Volatile private var notifAlertJob: Job? = null

    private val dismissedEventIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // Now Bar's own hidden activities never affect unlocked Dynamic Bar events.
    private val hiddenNowBarEventIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Re-select music once per lockscreen entry/wake; never override manual swipes.
    @Volatile private var prioritizeMusicOnKeyguardEntry = false

    override var onFocusableRequested: ((Boolean) -> Unit)? = null

    var onCollapseRequested: (() -> Unit)? = null

    private var isInitialized = false

    @Volatile private var panelBlocking = false
    private val _isPanelExpanded = MutableStateFlow(false)
    
    val isPanelExpanded: StateFlow<Boolean> = _isPanelExpanded.asStateFlow()
    
    val qsExpansion: StateFlow<Float> = shadeInteractor.qsExpansion
    
    val legacyShadeExpansion: StateFlow<Float> = shadeRepository.legacyShadeExpansion
    val mediaSeekBarStyle: StateFlow<MediaSeekBarStyle> = settings.seekBarStyle
    private val _isOnKeyguard = MutableStateFlow(false)
    val isOnKeyguard: StateFlow<Boolean> = _isOnKeyguard.asStateFlow()
    private val _isKeyguardFadingAway = MutableStateFlow(false)
    val isKeyguardFadingAway: StateFlow<Boolean> = _isKeyguardFadingAway.asStateFlow()
    private val _isBouncerShowing = MutableStateFlow(false)
    val isBouncerShowing: StateFlow<Boolean> = _isBouncerShowing.asStateFlow()
    private val _isDozing = MutableStateFlow(statusBarStateController.isDozing)
    val isDozing: StateFlow<Boolean> = _isDozing.asStateFlow()
    private val _dozeAmount = MutableStateFlow(0f)
    
    val dozeAmount: StateFlow<Float> = _dozeAmount.asStateFlow()
    private val _isDreaming = MutableStateFlow(statusBarStateController.isDreaming)
    val isDreaming: StateFlow<Boolean> = _isDreaming.asStateFlow()

    private val statusBlocking: Boolean
        get() = _isDozing.value || _isDreaming.value

    companion object {
        private const val TAG = "AxDynamicBarInteractor"
        private const val NOTIF_ALERT_DURATION_MS = 4500L

        private fun IslandEvent.Notification.isActiveCall(): Boolean {
            val extras = sbn.notification?.extras ?: return false
            return extras.containsKey(Notification.EXTRA_ANSWER_INTENT) ||
                extras.containsKey(Notification.EXTRA_DECLINE_INTENT) ||
                extras.containsKey(Notification.EXTRA_HANG_UP_INTENT)
        }
    }

    fun init() {
        if (isInitialized) return
        isInitialized = true

        settings.init()

        repository.system.onChargingStarted = { scheduleAutoDismiss(it) }
        repository.system.onRingerChanged = { scheduleAutoDismiss(it) }
        repository.system.onClipboardCopied = { scheduleAutoDismiss(it) }

        repository.notification.activeMediaPackageProvider = { repository.media.activeMediaPackage }
        repository.notification.onNotificationPosted = repository.notification::coalesceNotification
        repository.notification.onNotificationRemoved = { key ->
            if (_uiState.value.notificationAlert?.sbn?.key == key) dismissNotificationAlert()
        }
        repository.notification.onAlarmEvent = {
            scheduleAutoDismiss(it, if (it.isRinging) 30_000L else 5_000L)
        }

        repository.media.onMediaSessionLost = { repository.media.clear() }

        repository.biometric.onBiometricUnlock = { scheduleAutoDismiss(it) }

        applicationScope.launch {
            repository.notification.audioRecordingEvent.collect { event ->
                if (event?.state == RecordingState.SAVED) {
                    scheduleAutoDismiss(event, 5_000L)
                }
            }
        }

        applicationScope.launch {
            repository.notification.notificationFlow.collect { notification ->
                if (!repository.notification.isActiveNotification(notification)) return@collect
                showNotificationAlert(notification)
            }
        }

        applicationScope.launch {
            combine(
                _uiState.map { state ->
                    state.shouldShow &&
                        state.events.any { it is IslandEvent.Media && it.isPlaying && it.duration > 0L }
                },
                _isPanelExpanded,
                qsExpansion.map { it > 0f },
                powerInteractor.isInteractive,
                _isDozing,
            ) { mediaActive, panelExpanded, qsOpen, interactive, dozing ->
                mediaActive && !panelExpanded && !qsOpen && interactive && !dozing
            }.distinctUntilChanged().collect { needsPolling ->
                if (needsPolling) repository.media.startProgressPolling()
                else repository.media.stopProgressPolling()
            }
        }

        _isOnKeyguard.value = statusBarStateController.state == StatusBarState.KEYGUARD
        repository.setNowBarLockscreenActive(_isOnKeyguard.value && !_isDozing.value)
        prioritizeMusicOnKeyguardEntry = _isOnKeyguard.value && !_isDozing.value
        _isKeyguardFadingAway.value = keyguardStateController.isKeyguardFadingAway
        _isBouncerShowing.value = keyguardStateController.isPrimaryBouncerShowing

        keyguardStateController.addCallback(
            object : KeyguardStateController.Callback {
                override fun onKeyguardFadingAwayChanged() {
                    _isKeyguardFadingAway.value = keyguardStateController.isKeyguardFadingAway
                }

                override fun onPrimaryBouncerShowingChanged() {
                    _isBouncerShowing.value = keyguardStateController.isPrimaryBouncerShowing
                }

                override fun onUnlockedChanged() {
                    _lockscreenPrivacyTick.update { it + 1 }
                }
            }
        )

        lockscreenUserManager.addNotificationStateChangedListener {
            _lockscreenPrivacyTick.update { it + 1 }
        }

        keyguardNotificationVisibilityProvider.addOnStateChangedListener {
            _lockscreenPrivacyTick.update { it + 1 }
        }

        notifCollection.addCollectionListener(
            object : NotifCollectionListener {
                override fun onRankingApplied() {
                    if (_isOnKeyguard.value) {
                        _lockscreenPrivacyTick.update { it + 1 }
                    }
                }
            }
        )

        statusBarStateController.addCallback(
            object : StatusBarStateController.StateListener {
                override fun onExpandedChanged(isExpanded: Boolean) {
                    onPanelExpandedChanged(isExpanded)
                }

                override fun onStateChanged(newState: Int) {
                    val entering = newState == StatusBarState.KEYGUARD && !_isOnKeyguard.value
                    if (entering && !_isDozing.value) prioritizeMusicOnKeyguardEntry = true
                    else if (newState != StatusBarState.KEYGUARD) prioritizeMusicOnKeyguardEntry = false
                    _isOnKeyguard.value = newState == StatusBarState.KEYGUARD
                    repository.setNowBarLockscreenActive(_isOnKeyguard.value && !_isDozing.value)
                    refreshNowBarNotifications()
                    if (newState != StatusBarState.SHADE) dismissNotificationAlert()
                    updateChipVisibility()
                }

                override fun onDozingChanged(dozing: Boolean) {
                    if (_isDozing.value && !dozing && _isOnKeyguard.value) prioritizeMusicOnKeyguardEntry = true
                    _isDozing.value = dozing
                    repository.setNowBarLockscreenActive(_isOnKeyguard.value && !dozing)
                    updateChipVisibility()
                }

                override fun onDozeAmountChanged(linear: Float, eased: Float) {
                    _dozeAmount.value = linear
                }

                override fun onDreamingChanged(dreaming: Boolean) {
                    _isDreaming.value = dreaming
                    updateChipVisibility()
                }
            }
        )

        indicationController.addIndicationListener { type, text ->
            val indicationType = mapIndicationType(type) ?: return@addIndicationListener
            if (indicationType == IslandEvent.KeyguardIndication.IndicationType.BIOMETRIC &&
                !settings.isKeyguardBiometricUnlockEventsActive()) {
                return@addIndicationListener
            }
            if (text != null && text.isNotEmpty()) {
                val event = IslandEvent.KeyguardIndication(
                    text = text.toString(),
                    indicationType = indicationType,
                )
                repository.updateIndicationEvent(event)
                scheduleAutoDismiss(event)
            } else {
                repository.clearIndicationEvent(indicationType)
            }
        }

        applicationScope.launch {
            combine(
                settings.isEnabled,
                settings.isKeyguardEnabled,
                settings.isKeyguardMusicPillEnabled,
                settings.disabledEventTypes,
                combine(
                    settings.keyguardDisabledEventTypes,
                    settings.nowBarAppearance.map { it.sourceTypes }.distinctUntilChanged(),
                    settings.isKeyguardColorOsStyleEnabled,
                ) { disabled, sources, colorOs -> Triple(disabled, sources, colorOs) },
            ) { enabled, keyguardEnabled, pillEnabled, _, (_, sources, _) ->
                Triple(enabled || sources != null, keyguardEnabled, pillEnabled)
            }.collect { (enabled, keyguardEnabled, pillEnabled) ->
                if (!settings.isNotificationEventsActive()) dismissNotificationAlert()
                if (enabled || keyguardEnabled || pillEnabled) {
                    repository.startListening()
                    refreshNowBarNotifications()
                } else {
                    repository.notification.updateVisibleNotifications = false
                    repository.stopListening()
                    autoDismissJobs.values.forEach { it.cancel() }
                    autoDismissJobs.clear()
                    dismissedEventIds.clear()
                    hiddenNowBarEventIds.clear()
                    repository.clearAllIndicationEvents()
                    _uiState.value = IslandUiState()
                }
            }
        }

        applicationScope.launch {
            settings.isHeadsUpEnabled.collect { enabled ->
                if (!enabled) dismissNotificationAlert()
            }
        }

        applicationScope.launch {
            combine(
                repository.events.onEach { events -> hiddenNowBarEventIds.removeAll { id -> events.none { it.id == id } } },
                combine(settings.disabledEventTypes, settings.keyguardDisabledEventTypes) { a, b ->
                    a to b
                },
                combine(settings.isEnabled, settings.isKeyguardEnabled) { enabled, kgEnabled ->
                    enabled to kgEnabled
                },
                combine(settings.isKeyguardMusicPillEnabled, _lockscreenPrivacyTick, settings.nowBarAppearance.distinctUntilChangedBy { it.sourceTypes to it.liveApps }) { pill, _, nowBar ->
                    pill to nowBar
                }.combine(settings.isKeyguardColorOsStyleEnabled) { pair, colorOs -> Triple(pair.first, pair.second, colorOs) },
                combine(_isOnKeyguard, _isDozing) { kg, doze -> kg to doze },
            ) { raw, _, (enabled, kgEnabled), (pillEnabled, nowBar, _), (kg, _) ->
                raw.filter { e ->
                    if (kg && nowBar.enabled) {
                        nowBar.shows(e)
                    } else if (kg && e is IslandEvent.Media) {
                        // The lock screen music pill has its own switch and event source.
                        pillEnabled
                    } else {
                        (if (kg) kgEnabled else enabled) && settings.isEventEnabled(e, kg)
                    }
                } to kg
            }.collect { (rawEvents, onKeyguard) ->
                dismissedEventIds.removeAll { id -> rawEvents.none { it.id == id } }
                val events = rawEvents.mapNotNull { e ->
                    if (e.id in dismissedEventIds) return@mapNotNull null
                    if (!onKeyguard && e is IslandEvent.Notification) return@mapNotNull null
                    if (onKeyguard && settings.nowBarAppearance.value.enabled && e.id in hiddenNowBarEventIds) return@mapNotNull null
                    if (onKeyguard && e is IslandEvent.Charging && !settings.nowBarAppearance.value.enabled) return@mapNotNull null
                    if (onKeyguard && e is IslandEvent.AppSwitch) return@mapNotNull null
                    if (!onKeyguard && e is IslandEvent.KeyguardIndication) return@mapNotNull null
                    if (onKeyguard && e is IslandEvent.Notification) {
                        applyKeyguardNotificationPrivacy(e)?.let { notification ->
                            if (settings.nowBarAppearance.value.enabled && notification.isRedacted) {
                                // A redacted event must not retain conversation text, artwork or actions in the Now Bar UI.
                                notification.copy(title = null, text = null, senderName = null,
                                    senderIcon = null, conversationTitle = null, notificationImage = null,
                                    actions = emptyList(), replyAction = null, isConversation = false,
                                    isGroupConversation = false)
                            } else notification
                        }
                    } else {
                        e
                    }
                }

                val current = _uiState.value

                val hasNewEvents = events.any { e -> current.events.none { it.id == e.id } }

                val userInitiatedRefresh =
                    events.any { e ->
                        e is IslandEvent.Torch &&
                            current.events.firstOrNull { it.id == e.id }?.let { it != e } == true
                    }

                val hasSignificantChange =
                    !hasNewEvents &&
                        events.any { e ->
                            val old =
                                current.events.firstOrNull { it.id == e.id } ?: return@any false
                            when {
                                e is IslandEvent.Media && old is IslandEvent.Media ->
                                    e.track != old.track || e.artist != old.artist
                                e is IslandEvent.Notification && old is IslandEvent.Notification ->
                                    e.title != old.title || e.text != old.text ||
                                        e.senderName != old.senderName
                                else -> false
                            }
                        }

                val newState =
                    when {
                        events.isEmpty() -> IslandState.HIDDEN
                        panelBlocking || statusBlocking -> IslandState.HIDDEN
                        else -> IslandState.CHIP
                    }

                val prevPinnedId =
                    current.events.getOrNull(current.pinnedEventIndex)?.id

                // Keep biometric unlock feedback in the primary pill.
                val musicOnEntryIndex = if (onKeyguard && settings.nowBarAppearance.value.enabled && prioritizeMusicOnKeyguardEntry) {
                    events.indexOfFirst { it is IslandEvent.Media && it.isPlaying }.takeIf { it >= 0 }
                        ?: events.indexOfFirst { it is IslandEvent.Media }
                } else -1
                if (musicOnEntryIndex >= 0) prioritizeMusicOnKeyguardEntry = false
                val unlockIndex = events.indexOfFirst { it is IslandEvent.BiometricUnlock }
                val pinnedIndex =
                    when {
                        unlockIndex >= 0 -> unlockIndex
                        musicOnEntryIndex >= 0 -> musicOnEntryIndex
                        hasNewEvents -> {
                            val currentIds = current.events.map { it.id }.toSet()
                            events.indexOfFirst { it.id !in currentIds }.coerceAtLeast(0)
                        }
                        hasSignificantChange -> {
                            val idx =
                                events.indexOfFirst { e ->
                                    val old =
                                        current.events.firstOrNull { it.id == e.id }
                                            ?: return@indexOfFirst false
                                    when {
                                        e is IslandEvent.Media && old is IslandEvent.Media ->
                                            e.track != old.track || e.artist != old.artist
                                        e is IslandEvent.Notification && old is IslandEvent.Notification ->
                                            e.title != old.title || e.text != old.text ||
                                                e.senderName != old.senderName
                                        else -> false
                                    }
                                }
                            if (idx >= 0) idx
                            else resolveByIdOrFallback(prevPinnedId, events, current)
                        }
                        userInitiatedRefresh -> 0
                        else -> resolveByIdOrFallback(prevPinnedId, events, current)
                    }
                val shouldReset = hasNewEvents || userInitiatedRefresh || hasSignificantChange

                _uiState.value =
                    IslandUiState(
                        events = events,
                        islandState = newState,
                        pinnedEventIndex = pinnedIndex,
                        manuallyHidden = if (shouldReset) false else current.manuallyHidden,
                        forceVisible = false,
                        notificationAlert = current.notificationAlert,
                    )
            }
        }
    }

    /**
     * Hide a single Now Bar activity until its source ends, without stopping
     * playback, a timer, a call, or the torch.
     */
    fun hideNowBarEvent(event: IslandEvent) {
        if (!settings.nowBarAppearance.value.enabled) return
        hiddenNowBarEventIds.add(event.id)
        val current = _uiState.value
        val remaining = current.events.filterNot { it.id == event.id }
        _uiState.value = current.copy(
            events = remaining,
            pinnedEventIndex = current.pinnedEventIndex.coerceAtMost(
                (remaining.size - 1).coerceAtLeast(0)),
            islandState = if (remaining.isEmpty()) IslandState.HIDDEN
                else current.islandState,
        )
    }

    fun cycleNext() {
        prioritizeMusicOnKeyguardEntry = false
        val current = _uiState.value
        if (current.events.size <= 1) return
        val next = (current.pinnedEventIndex + 1) % current.events.size
        _uiState.value = current.copy(pinnedEventIndex = next)
    }

    fun cyclePrev() {
        prioritizeMusicOnKeyguardEntry = false
        val current = _uiState.value
        if (current.events.size <= 1) return
        val prev = (current.pinnedEventIndex - 1 + current.events.size) % current.events.size
        _uiState.value = current.copy(pinnedEventIndex = prev)
    }

    fun pinEventAt(index: Int) {
        prioritizeMusicOnKeyguardEntry = false
        val current = _uiState.value
        if (index < 0 || index >= current.events.size) return
        _uiState.value = current.copy(pinnedEventIndex = index)
    }

    override fun dismissEvent(event: IslandEvent) {
        autoDismissJobs[event.id]?.cancel()
        autoDismissJobs.remove(event.id)
        if (event.behavior.suppressOnDismiss) {
            dismissedEventIds.add(event.id)
        }

        val current = _uiState.value
        val updatedEvents = current.events.filter { it.id != event.id }
        val newIndex =
            current.pinnedEventIndex.coerceAtMost((updatedEvents.size - 1).coerceAtLeast(0))

        _uiState.value =
            current.copy(
                events = updatedEvents,
                pinnedEventIndex = newIndex,
                islandState =
                    if (updatedEvents.isEmpty()) IslandState.HIDDEN else current.islandState,
            )

        when (event) {
            is IslandEvent.AudioRecording -> repository.notification.clearAudioRecording()
            is IslandEvent.Sports -> {
                repository.smartspace.clearSportsEvent(event.key)
                repository.notification.clearSportsEvent(event.key)
            }
            is IslandEvent.NowPlaying -> {}
            is IslandEvent.PromotedOngoing ->
                repository.notification.clearPromotedOngoing(event.sbn.key)
            is IslandEvent.Media -> repository.media.clear()
            is IslandEvent.Bluetooth -> repository.connectivity.clearBluetooth()
            is IslandEvent.Hotspot -> repository.connectivity.clearHotspot()
            is IslandEvent.Charging -> repository.system.clearCharging()
            is IslandEvent.Alarm -> repository.notification.clearAlarm()
            is IslandEvent.Call -> repository.notification.clearCall(event.sbn.key)
            is IslandEvent.Timer -> repository.notification.clearTimer()
            is IslandEvent.Stopwatch -> repository.notification.clearStopwatch()
            is IslandEvent.RingerMode -> repository.system.clearRinger()
            is IslandEvent.Vpn -> repository.connectivity.clearVpn()
            is IslandEvent.Clipboard -> repository.system.clearClipboard()
            is IslandEvent.Notification -> repository.notification.dismissNotification(event)
            is IslandEvent.AppSwitch -> repository.appTracking.clear()
            is IslandEvent.Torch -> {
                if (repository.torch.isEnabled) {
                    repository.torch.toggleTorch()
                }
                repository.torch.clear()
            }
            is IslandEvent.BiometricUnlock -> repository.biometric.clear()
            is IslandEvent.KeyguardIndication -> repository.clearIndicationEvent(event.indicationType)
            is IslandEvent.AospChip -> {}
        }
    }

    fun getTopEvent(): IslandEvent? = _uiState.value.topEvent

    override fun collapseIsland() {
        onCollapseRequested?.invoke()
    }

    override fun onNotificationInteraction(eventId: String) {
        autoDismissJobs[eventId]?.cancel()
        autoDismissJobs.remove(eventId)
    }

    override fun onNotificationInteractionEnd(eventId: String) {
        // Keyguard can filter out charging before the status bar panel finishes closing.
        val event = _uiState.value.events.find { it.id == eventId }
            ?: repository.system.chargingEvent.value?.takeIf { it.id == eventId }
            ?: return
        scheduleAutoDismiss(event)
    }

    override fun onNotificationAlertInteractionStart(eventId: String) {
        if (_uiState.value.notificationAlert?.id != eventId) return
        notifAlertJob?.cancel()
        notifAlertJob = null
    }

    override fun onNotificationAlertInteractionEnd(eventId: String) {
        val alert = _uiState.value.notificationAlert ?: return
        if (alert.id != eventId) return
        notifAlertJob?.cancel()
        notifAlertJob = null
        if (alert.isActiveCall() || alert.progress >= 0 || alert.isProgressIndeterminate) return
        notifAlertJob = applicationScope.launch {
            delay(NOTIF_ALERT_DURATION_MS)
            if (_uiState.value.notificationAlert?.id == eventId) dismissNotificationAlert()
        }
    }

    fun dismissNotificationAlert() {
        notifAlertJob?.cancel()
        notifAlertJob = null
        val current = _uiState.value
        if (current.notificationAlert != null) {
            _uiState.value = current.copy(notificationAlert = null)
        }
    }

    private fun shouldSuppressForDndOrRinger(notification: IslandEvent.Notification): Boolean =
        com.android.systemui.islandevents.domain.NotificationAlertPolicy.shouldSuppress(
            notification,
            enabled = settings.isHeadsUpEnabled.value,
            zenMode = zenModeController.zen,
            ringerMode = audioManager.ringerMode,
        )

    private fun showNotificationAlert(
        notification: IslandEvent.Notification,
    ) {
        if (!settings.isNotificationEventsActive()) return
        if (panelBlocking || statusBlocking || _isOnKeyguard.value) return
        if (shouldSuppressForDndOrRinger(notification)) return
        val current = _uiState.value
        val existingAlert = current.notificationAlert
        if (existingAlert != null &&
            existingAlert.isActiveCall() &&
            !notification.isActiveCall()
        ) return

        val hasProgress = notification.progress >= 0 || notification.isProgressIndeterminate
        val isSameKey = existingAlert != null && existingAlert.sbn.key == notification.sbn.key

        if (isSameKey && hasProgress) {
            _uiState.value = current.copy(notificationAlert = notification)
            return
        }

        notifAlertJob?.cancel()
        _uiState.value = current.copy(notificationAlert = notification)

        if (hasProgress) return

        val duration =
            if (!notification.isActiveCall()) NOTIF_ALERT_DURATION_MS else null
        if (duration != null) {
            notifAlertJob = applicationScope.launch {
                delay(duration)
                dismissNotificationAlert()
            }
        }
    }

    override fun togglePlayPause() = repository.media.togglePlayPause()

    override fun skipNext() = repository.media.skipNext()

    override fun skipPrev() = repository.media.skipPrev()

    override fun sendCustomAction(action: String) = repository.media.sendCustomAction(action)

    override fun openMediaOutputSwitcher() = repository.media.openMediaOutputSwitcher()

    override fun disconnectBluetooth(address: String) = repository.connectivity.disconnectBluetooth(address)

    override fun openUrl(url: String) = repository.system.openUrl(url)

    override fun openMediaApp() = repository.media.openMediaApp()

    override fun seekTo(position: Long) = repository.media.seekTo(position)

    override fun setRingerMode(mode: Int) = repository.system.setRingerMode(mode)

    override fun toggleTorch() = repository.torch.toggleTorch()

    override fun launchNotificationDismissingKeyguard(event: IslandEvent.Notification) {
        val intent = event.sbn.notification?.contentIntent ?: return
        activityStarter.startPendingIntentDismissingKeyguard(intent)
    }

    override fun setTorchLevel(level: Int) = repository.torch.setLevel(level)

    override fun setTorchLevelTemporary(level: Int) = repository.torch.setLevelTemporary(level)

    override fun copyToClipboard(text: String) = repository.system.copyToClipboard(text)

    override fun copyUriToClipboard(uri: Uri) = repository.system.copyUriToClipboard(uri)

    override fun removeClipboardItem(id: Long) = repository.system.removeClipboardItem(id)

    override fun switchToApp(taskId: Int) = repository.appTracking.switchToApp(taskId)

    fun switchToPreviousApp(event: IslandEvent.AppSwitch) {
        val previousApp = event.previousApp ?: return
        val currentApp = event.recentApps.firstOrNull()
        if (previousApp.taskId < 0 || previousApp.taskId == currentApp?.taskId ||
            previousApp.packageName == currentApp?.packageName
        ) {
            return
        }
        repository.appTracking.switchToApp(previousApp.taskId)
    }

    override fun killApp(taskId: Int) = repository.appTracking.killApp(taskId)

    fun onPanelExpandedChanged(expanded: Boolean) {
        panelBlocking = expanded
        _isPanelExpanded.value = expanded
        if (expanded) dismissNotificationAlert()
        updateChipVisibility()
    }

    private fun updateChipVisibility() {
        val current = _uiState.value
        val shouldHide = panelBlocking || statusBlocking
        if (shouldHide && current.islandState == IslandState.CHIP) {
            _uiState.value = current.copy(islandState = IslandState.HIDDEN)
        } else if (!shouldHide && current.events.isNotEmpty() && current.islandState == IslandState.HIDDEN && !current.manuallyHidden) {
            _uiState.value = current.copy(islandState = IslandState.CHIP)
        }
    }

    private fun scheduleAutoDismiss(event: IslandEvent, delayOverride: Long? = null) {
        if (event is IslandEvent.Charging && _isOnKeyguard.value && settings.nowBarAppearance.value.let { it.enabled && it.charging }) return
        if (!settings.isAnyBarEnabled() && !settings.nowBarAppearance.value.shows(event)) return
        val ms = delayOverride ?: event.behavior.autoDismissMs ?: return
        val eventId = event.id
        autoDismissJobs[eventId]?.cancel()
        autoDismissJobs[eventId] =
            applicationScope.launch {
                delay(ms)
                val current = _uiState.value.events.find { it.id == eventId } ?: event
                dismissEvent(current)
            }
    }

    private fun refreshNowBarNotifications() {
        val active = _isOnKeyguard.value && settings.isNowBarNotificationEnabled()
        repository.notification.updateVisibleNotifications = active
        if (active) {
            repository.notification.refreshLockscreenNotifications(
                notifCollection.allNotifs.map { it.sbn },
            )
        }
    }

    private fun applyKeyguardNotificationPrivacy(
        event: IslandEvent.Notification,
    ): IslandEvent.Notification? {
        if (!lockscreenUserManager.shouldShowLockscreenNotifications()) return null
        val entry = notifCollection.getEntry(event.sbn.key)
        // Do not show a Now Bar notification that cannot be validated against the live collection.
        if (entry == null && settings.nowBarAppearance.value.enabled) return null
        if (entry != null && keyguardNotificationVisibilityProvider.shouldHideNotification(entry)) {
            return null
        }
        val notif = event.sbn.notification ?: return event
        if (notif.visibility == Notification.VISIBILITY_SECRET) return null
        val userId = event.sbn.user.identifier.let {
            if (it == UserHandle.USER_ALL) lockscreenUserManager.currentUserId else it
        }
        val isLocked =
            keyguardStateController.isMethodSecure && !keyguardStateController.canDismissLockScreen()
        if (isLocked && !lockscreenUserManager.userAllowsNotificationsInPublic(userId)) {
            return null
        }
        val shouldRedact =
            isLocked &&
                notif.visibility == Notification.VISIBILITY_PRIVATE &&
                !lockscreenUserManager.userAllowsPrivateNotificationsInPublic(userId)
        return if (shouldRedact) event.copy(isRedacted = true) else event
    }

    private fun resolveByIdOrFallback(
        pinnedId: String?,
        events: List<IslandEvent>,
        current: IslandUiState,
    ): Int {
        if (pinnedId != null) {
            val idx = events.indexOfFirst { it.id == pinnedId }
            if (idx >= 0) return idx
        }
        return current.pinnedEventIndex.coerceAtMost(
            (events.size - 1).coerceAtLeast(0)
        )
    }

    private fun mapIndicationType(type: Int): IslandEvent.KeyguardIndication.IndicationType? =
        when (type) {
            KeyguardIndicationController.AX_TYPE_BIOMETRIC ->
                IslandEvent.KeyguardIndication.IndicationType.BIOMETRIC
            KeyguardIndicationController.AX_TYPE_TRANSIENT ->
                IslandEvent.KeyguardIndication.IndicationType.TRANSIENT
            KeyguardIndicationController.AX_TYPE_TRUST ->
                IslandEvent.KeyguardIndication.IndicationType.TRUST
            KeyguardIndicationController.AX_TYPE_DISCLOSURE ->
                IslandEvent.KeyguardIndication.IndicationType.DISCLOSURE
            KeyguardIndicationController.AX_TYPE_OWNER_INFO ->
                IslandEvent.KeyguardIndication.IndicationType.OWNER_INFO
            KeyguardIndicationController.AX_TYPE_ALIGNMENT ->
                IslandEvent.KeyguardIndication.IndicationType.ALIGNMENT
            KeyguardIndicationController.AX_TYPE_PERSISTENT_UNLOCK ->
                IslandEvent.KeyguardIndication.IndicationType.PERSISTENT_UNLOCK
            else -> null
        }
}
