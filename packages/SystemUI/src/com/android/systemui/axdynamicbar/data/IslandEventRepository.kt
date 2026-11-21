package com.android.systemui.axdynamicbar.data

import android.util.Log
import com.android.systemui.axdynamicbar.data.source.AospChipIslandManager
import com.android.systemui.axdynamicbar.data.source.AppTrackingIslandManager
import com.android.systemui.axdynamicbar.data.source.BiometricIslandManager
import com.android.systemui.axdynamicbar.data.source.ConnectivityIslandManager
import com.android.systemui.axdynamicbar.data.source.MediaIslandManager
import com.android.systemui.axdynamicbar.data.source.NotificationIslandManager
import com.android.systemui.axdynamicbar.data.source.SmartspaceIslandManager
import com.android.systemui.axdynamicbar.data.source.SystemIslandManager
import com.android.systemui.axdynamicbar.data.source.TorchIslandManager
import com.android.systemui.axdynamicbar.domain.AxDynamicBarSettings
import com.android.systemui.islandevents.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@SysUISingleton
class IslandEventRepository
@Inject
constructor(
    val media: MediaIslandManager,
    val connectivity: ConnectivityIslandManager,
    val system: SystemIslandManager,
    val notification: NotificationIslandManager,
    val appTracking: AppTrackingIslandManager,
    val torch: TorchIslandManager,
    val biometric: BiometricIslandManager,
    val smartspace: SmartspaceIslandManager,
    val aospChip: AospChipIslandManager,
    private val settings: AxDynamicBarSettings,
) {
    companion object {
        private const val TAG = "IslandEventRepository"
    }

    @Volatile private var listenersStarted = false
    // Only the visible lockscreen Now Bar uses persistent charging events.
    private var nowBarLockscreenActive = false

    fun setNowBarLockscreenActive(active: Boolean) {
        if (nowBarLockscreenActive == active) return
        nowBarLockscreenActive = active
        if (listenersStarted) refreshChargingListener()
    }

    // A change to Now Bar's options must recompute the existing source events
    // even if the underlying media/notification objects have not changed.
    private val settingsRevision = MutableStateFlow(0)

    private val _indicationEvents =
        MutableStateFlow<Map<String, IslandEvent.KeyguardIndication>>(emptyMap())

    fun updateIndicationEvent(event: IslandEvent.KeyguardIndication) {
        _indicationEvents.update { it + (event.indicationType.name to event) }
    }

    fun clearIndicationEvent(type: IslandEvent.KeyguardIndication.IndicationType) {
        _indicationEvents.update { it - type.name }
    }

    fun clearAllIndicationEvents() {
        _indicationEvents.value = emptyMap()
    }

    val events: Flow<List<IslandEvent>> = buildEventsFlow()

    private val disabled get() = settings.disabledEventTypes.value

    private fun isNowBarTypeEnabled(typeId: String): Boolean {
        val nowBar = settings.nowBarAppearance.value
        if (!nowBar.enabled) return false
        return when (typeId) {
            "media", "now_playing" -> nowBar.music
            "timer", "stopwatch", "alarm" -> nowBar.timers
            "call" -> nowBar.calls
            "promoted_ongoing" -> nowBar.live
            "sports" -> nowBar.scores
            "audio_recording" -> nowBar.recording
            "charging" -> nowBar.charging
            "torch" -> nowBar.flashlight
            "bluetooth" -> nowBar.buds
            else -> false
        }
    }

    // Dynamic Bar has its own event-type settings. Now Bar only adds its
    // independently selected activity sources; it never changes those settings.
    private fun isTypeEnabled(typeId: String): Boolean =
        (settings.isEnabled.value && typeId !in disabled) || isNowBarTypeEnabled(typeId)

    private fun isMediaSourceNeeded(): Boolean =
        isTypeEnabled("media") || settings.isKeyguardMusicPillEnabled.value

    private fun needsNowBarNotifications(): Boolean = listOf(
        "timer", "stopwatch", "alarm", "call", "promoted_ongoing",
        "sports", "audio_recording", "now_playing",
    ).any(::isNowBarTypeEnabled)


    fun startListening() {
        if (!listenersStarted) {
            listenersStarted = true
            Log.d(TAG, "Starting event listeners")
        }
        refreshListeners()
    }

    fun stopListening() {
        if (!listenersStarted) return
        listenersStarted = false
        Log.d(TAG, "Stopping event listeners")
        media.stopListening()
        connectivity.stopListening()
        system.stopListening()
        notification.stopListening()
        torch.stopListening()
        appTracking.stopListening()
        biometric.stopListening()
        smartspace.stopListening()
    }

    fun refreshListeners() {
        if (!listenersStarted) return
        syncDisabledTypes()

        if (isMediaSourceNeeded()) media.startListening()
        else media.stopListening()

        if (settings.isEnabled.value || needsNowBarNotifications()) notification.startListening()
        else notification.stopListening()

        if (isTypeEnabled("bluetooth")) connectivity.startBluetooth()
        else connectivity.stopBluetooth()
        if (isTypeEnabled("hotspot")) connectivity.startHotspot()
        else connectivity.stopHotspot()
        if (isTypeEnabled("vpn")) connectivity.startVpn()
        else connectivity.stopVpn()

        refreshChargingListener()
        if (isTypeEnabled("ringer")) system.startRinger()
        else system.stopRinger()
        if (isTypeEnabled("clipboard")) system.startClipboard()
        else system.stopClipboard()

        if (isTypeEnabled("app_switch")) appTracking.startListening()
        else appTracking.stopListening()
        if (isTypeEnabled("torch")) torch.startListening()
        else torch.stopListening()
        if (isTypeEnabled("biometric_unlock")) biometric.startListening()
        else biometric.stopListening()

        if (isTypeEnabled("media") || isTypeEnabled("sports")) smartspace.startListening()
        else smartspace.stopListening()

        settingsRevision.value += 1
    }

    private fun refreshChargingListener() {
        // While the lockscreen Now Bar is visible, show charging until unplugged.
        // Elsewhere, keep Dynamic Bar's original short charging alert behavior.
        val persistentCharging = nowBarLockscreenActive && isNowBarTypeEnabled("charging")
        if (system.persistWhilePluggedIn != persistentCharging) {
            system.stopCharging()
            system.persistWhilePluggedIn = persistentCharging
        }
        if (isTypeEnabled("charging")) system.startCharging()
        else system.stopCharging()
    }

    private fun syncDisabledTypes() {
        // A disabled Dynamic Bar type must not suppress an enabled Now Bar source.
        notification.disabledTypes = disabled.filterNot(::isNowBarTypeEnabled).toSet()
    }

    private fun buildEventsFlow(): Flow<List<IslandEvent>> {

        val sportsGroup = combine(
            smartspace.sportsEvents,
            notification.sportsEvents,
            settingsRevision,
        ) { qlSports, notifSports, _ ->
            if (!isTypeEnabled("sports")) emptyList()
            else qlSports + notifSports.filter { ns ->
                qlSports.none { qs ->
                    qs.team1Name.equals(ns.team1Name, ignoreCase = true) &&
                        qs.team2Name.equals(ns.team2Name, ignoreCase = true)
                }
            }
        }
        val promotedGroup = combine(
            notification.promotedOngoingEvents,
            sportsGroup,
        ) { promoted, sports ->
            (if (isTypeEnabled("promoted_ongoing")) promoted else emptyList()) + sports
        }
        val highGroup = combine(
            notification.callEvents,
            torch.torchEvent,
            biometric.biometricEvent,
            settingsRevision,
        ) { call, t, bio, _ ->
                (if (isTypeEnabled("call")) call else emptyList()) +
                listOfNotNull(
                    t?.takeIf { isTypeEnabled("torch") },
                    bio?.takeIf { isTypeEnabled("biometric_unlock") },
                )
            }
        val midGroup =
            combine(
                media.mediaEvent.combine(settingsRevision) { item, _ -> item },
                connectivity.bluetoothEvent,
                connectivity.hotspotEvent,
                system.chargingEvent,
                notification.alarmEvent,
            ) { m, bt, hotspot, charging, alarm ->
                listOfNotNull(
                    m?.takeIf { isMediaSourceNeeded() },
                    bt?.takeIf { isTypeEnabled("bluetooth") },
                    hotspot?.takeIf { isTypeEnabled("hotspot") },
                    charging?.takeIf { isTypeEnabled("charging") },
                    alarm?.takeIf { isTypeEnabled("alarm") },
                )
            }
        val lowGroupA =
            combine(
                notification.timerEvent.combine(settingsRevision) { item, _ -> item },
                notification.stopwatchEvent,
                system.ringerEvent,
                connectivity.vpnEvent,
                system.clipboardEvent,
            ) { timer, stopwatch, ringer, vpn, clipboard ->
                listOfNotNull(
                    timer?.takeIf { isTypeEnabled("timer") },
                    stopwatch?.takeIf { isTypeEnabled("stopwatch") },
                    ringer?.takeIf { isTypeEnabled("ringer") },
                    vpn?.takeIf { isTypeEnabled("vpn") },
                    clipboard?.takeIf { isTypeEnabled("clipboard") },
                )
            }
        val lowGroup =
            combine(
                lowGroupA,
                appTracking.appSwitchEvent,
                notification.audioRecordingEvent,
                smartspace.nowPlayingEvent,
            ) { a, appSwitch, audioRec, nowPlaying ->
                a + listOfNotNull(
                    appSwitch?.takeIf { isTypeEnabled("app_switch") },
                    audioRec?.takeIf { isTypeEnabled("audio_recording") },
                    nowPlaying?.takeIf { isTypeEnabled("now_playing") || isTypeEnabled("media") },
                )
            }
        val transientGroup = combine(midGroup, lowGroup) { mid, low -> mid + low }

        val indicationGroup = _indicationEvents.map { it.values.toList() }

        val allEvents = combine(
            highGroup,
            transientGroup,
            promotedGroup,
            indicationGroup,
            aospChip.aospChipEvents,
        ) { high, transient, promoted, indication, aosp ->
            high + transient + promoted + indication + aosp
        }

        return allEvents.map { events ->
            events.sorted()
        }.distinctUntilChanged { old, new ->
            old.size == new.size && old.indices.all { i ->
                old[i].withoutDrawables() == new[i].withoutDrawables()
            }
        }
    }
}
