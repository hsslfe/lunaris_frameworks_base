/*
 * Copyright 2025-2026 AxionOS
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

package com.android.systemui.axdynamicbar.ui

import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.islandevents.model.IslandEvent
import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class AxDynamicBarKeyguardExpansion
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    private val interactor: AxDynamicBarInteractor,
    batteryInteractor: BatteryInteractor,
    batteryController: BatteryController,
) {
    private enum class Target { EVENT, CHARGING }
    private val target = MutableStateFlow<Target?>(null)

    val batteryInfo: StateFlow<KeyguardBatteryInfo> =
        combine(
            batteryInteractor.level,
            batteryInteractor.isCharging,
            batteryInteractor.powerSave,
            batteryInteractor.batteryTimeRemainingEstimate,
        ) { level, charging, powerSave, timeRemaining ->
            KeyguardBatteryInfo(
                level = level ?: 0,
                isCharging = charging,
                isPowerSave = powerSave,
                isWireless = batteryController.isPluggedInWireless,
                timeRemaining = timeRemaining,
            )
        }.stateIn(
            applicationScope, SharingStarted.WhileSubscribed(5000),
            KeyguardBatteryInfo(0, false, false, false, null),
        )

    private val chargingChip = combine(
        batteryInfo,
        interactor.settings.isKeyguardEnabled,
        interactor.settings.keyguardBatteryChipMode,
        interactor.settings.nowBarAppearance,
    ) { battery, enabled, mode, nowBar ->
        if (enabled && mode > 0 && !nowBar.enabled && battery.isCharging) {
            IslandEvent.Charging(
                level = battery.level, isWireless = battery.isWireless,
                isCharging = true, isPowerSave = battery.isPowerSave,
                timeRemaining = battery.timeRemaining,
            )
        } else null
    }.stateIn(applicationScope, SharingStarted.Eagerly, null)

    val panelEvent: StateFlow<IslandEvent?> =
        combine(interactor.uiState, chargingChip, target) { state, battery, requested ->
            if (requested == Target.CHARGING) battery else state.topEvent.takeIf { state.shouldShow }
        }.stateIn(applicationScope, SharingStarted.Eagerly, null)

    private val hasChip =
        combine(interactor.uiState, chargingChip, target) { state, battery, requested ->
            val eventPresent = state.shouldShow && state.topEvent != null
            when (requested) {
                Target.CHARGING -> battery != null
                Target.EVENT -> eventPresent
                null -> eventPresent || battery != null
            }
        }
            .distinctUntilChanged()
            .transformLatest { present ->
                // During a track switch the media event can momentarily vanish.
                // A short cancellable grace period avoids collapsing the whole
                // Now Bar and flashing the wallpaper for a transient empty list.
                if (!present && target.value == Target.EVENT) {
                    delay(280L)
                }
                emit(present)
            }
            .distinctUntilChanged()

    val canShow: StateFlow<Boolean> = run {
        val contextAndDoze =
            combine(
                interactor.isOnKeyguard,
                interactor.isDozing,
                interactor.dozeAmount.map { it > 0f }.distinctUntilChanged(),
                interactor.qsExpansion.map { it > 0f }.distinctUntilChanged(),
                interactor.isPanelExpanded,
            ) { onKg, dozing, dozeAmt, qs, panelExp ->
                onKg && !dozing && !dozeAmt && !qs && !panelExp
            }
        val shadeAndBouncer =
            combine(
                interactor.isBouncerShowing,
                interactor.legacyShadeExpansion.map { it >= 0.95f }.distinctUntilChanged(),
                hasChip,
                interactor.isDreaming,
            ) { bouncer, shadeFull, chip, dreaming ->
                !bouncer && shadeFull && chip && !dreaming
            }
        val settingsAllowKeyguard = combine(
            interactor.settings.isKeyguardEnabled,
            interactor.settings.isKeyguardMusicPillEnabled,
            interactor.settings.nowBarAppearance,
        ) { keyguardEnabled, musicPillEnabled, nowBar ->
            keyguardEnabled || musicPillEnabled || nowBar.enabled
        }
        combine(contextAndDoze, shadeAndBouncer, settingsAllowKeyguard) { a, b, enabled ->
            a && b && enabled
        }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)
    }

    val isExpanded: StateFlow<Boolean> =
        combine(target, canShow) { requested, show -> requested != null && show }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Lazily, false)

    private val _collapseSettled = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val collapseSettled: SharedFlow<Unit> = _collapseSettled.asSharedFlow()

    fun notifyCollapseSettled() {
        _collapseSettled.tryEmit(Unit)
    }

    init {
        canShow
            .onEach { if (!it) collapse() }
            .launchIn(applicationScope)

        interactor.isOnKeyguard
            .onEach { if (!it) collapse() }
            .launchIn(applicationScope)
    }

    fun expand() {
        val state = interactor.uiState.value
        val settings = interactor.settings
        val enabled = settings.nowBarAppearance.value.enabled || settings.isKeyguardEnabled.value ||
            settings.isKeyguardMusicPillEnabled.value
        if (!enabled || !state.shouldShow || state.topEvent == null ||
            !interactor.isOnKeyguard.value || interactor.isDozing.value ||
            interactor.dozeAmount.value > 0f || interactor.qsExpansion.value > 0f ||
            interactor.isPanelExpanded.value || interactor.isBouncerShowing.value ||
            interactor.legacyShadeExpansion.value < 0.95f
        ) return
        if (interactor.isDreaming.value) return
        target.value = Target.EVENT
    }

    fun expandCharging() {
        if (!canShow.value || chargingChip.value == null) return
        target.value = Target.CHARGING
    }

    fun collapse() {
        target.value = null
    }

    fun toggle() {
        if (target.value != null) collapse() else expand()
    }
}
