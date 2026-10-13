/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.feature.node.detail

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.model.Position
import org.meshtastic.core.model.TelemetryType
import org.meshtastic.core.repository.LocalNodeUnavailableException
import org.meshtastic.core.repository.PacketQueueRejectedException
import org.meshtastic.core.repository.PlatformAnalytics
import org.meshtastic.core.repository.RadioController
import org.meshtastic.core.repository.TimeSyncResult
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.UiText
import org.meshtastic.core.resources.neighbor_info
import org.meshtastic.core.resources.position
import org.meshtastic.core.resources.request_air_quality_metrics
import org.meshtastic.core.resources.request_device_metrics
import org.meshtastic.core.resources.request_environment_metrics
import org.meshtastic.core.resources.request_host_metrics
import org.meshtastic.core.resources.request_local_stats
import org.meshtastic.core.resources.request_pax_metrics
import org.meshtastic.core.resources.request_power_metrics
import org.meshtastic.core.resources.requesting_from
import org.meshtastic.core.resources.traceroute
import org.meshtastic.core.resources.user_info
import org.meshtastic.core.ui.util.SnackbarManager
import kotlin.time.Duration.Companion.seconds

/** How long a finished "Sync time" result stays on screen before the action returns to idle. */
private val SYNC_TIME_RESULT_VISIBLE = 30.seconds

@Single(binds = [NodeRequestActions::class])
class CommonNodeRequestActions
constructor(
    private val radioController: RadioController,
    private val snackbarManager: SnackbarManager,
    private val analytics: PlatformAnalytics,
    private val applicationScope: ApplicationCoroutineScope,
    private val resolveUiText: suspend (UiText) -> String = { it.resolve() },
) : NodeRequestActions {

    private val _lastTracerouteTime = MutableStateFlow<Long?>(null)
    override val lastTracerouteTime: StateFlow<Long?> = _lastTracerouteTime.asStateFlow()

    private val _lastRequestNeighborTimes = MutableStateFlow<Map<Int, Long>>(emptyMap())
    override val lastRequestNeighborTimes: StateFlow<Map<Int, Long>> = _lastRequestNeighborTimes.asStateFlow()

    private val _syncTimeStates = MutableStateFlow<Map<Int, SyncTimeState>>(emptyMap())
    override val syncTimeStates: StateFlow<Map<Int, SyncTimeState>> = _syncTimeStates.asStateFlow()

    private suspend fun showFeedback(text: UiText) {
        snackbarManager.showSnackbar(message = resolveUiText(text))
    }

    private suspend fun runRequest(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: PacketQueueRejectedException) {
            showNodeRequestFailure(e, "Node request rejected by outbound packet queue", snackbarManager, resolveUiText)
        } catch (e: LocalNodeUnavailableException) {
            showNodeRequestFailure(
                e,
                "Node request deferred until local node identity is available",
                snackbarManager,
                resolveUiText,
            )
        }
    }

    override suspend fun requestUserInfo(destNum: Int, longName: String) = runRequest {
        Logger.i { "Requesting UserInfo for '$destNum'" }
        radioController.requestUserInfo(destNum)
        analytics.trackAction("user_info_request")
        showFeedback(UiText.Resource(Res.string.requesting_from, Res.string.user_info, longName))
    }

    override suspend fun requestNeighborInfo(destNum: Int, longName: String) = runRequest {
        Logger.i { "Requesting NeighborInfo for '$destNum'" }
        val packetId = radioController.generatePacketId()
        radioController.requestNeighborInfo(packetId, destNum)
        _lastRequestNeighborTimes.update { it + (destNum to nowMillis) }
        showFeedback(UiText.Resource(Res.string.requesting_from, Res.string.neighbor_info, longName))
    }

    override suspend fun requestPosition(destNum: Int, longName: String, position: Position) = runRequest {
        Logger.i { "Requesting position for '$destNum'" }
        radioController.requestPosition(destNum, position)
        analytics.trackAction("position_request")
        showFeedback(UiText.Resource(Res.string.requesting_from, Res.string.position, longName))
    }

    override suspend fun requestTelemetry(destNum: Int, longName: String, type: TelemetryType) = runRequest {
        Logger.i { "Requesting telemetry for '$destNum'" }
        val packetId = radioController.generatePacketId()
        radioController.requestTelemetry(packetId, destNum, type.ordinal)
        analytics.trackAction("telemetry_request", mapOf("telemetry_type" to type.name))

        val typeRes =
            when (type) {
                TelemetryType.DEVICE -> Res.string.request_device_metrics
                TelemetryType.ENVIRONMENT -> Res.string.request_environment_metrics
                TelemetryType.AIR_QUALITY -> Res.string.request_air_quality_metrics
                TelemetryType.POWER -> Res.string.request_power_metrics
                TelemetryType.LOCAL_STATS -> Res.string.request_local_stats
                TelemetryType.HOST -> Res.string.request_host_metrics
                TelemetryType.PAX -> Res.string.request_pax_metrics
            }

        showFeedback(UiText.Resource(Res.string.requesting_from, typeRes, longName))
    }

    @Suppress("TooGenericExceptionCaught")
    override fun syncTime(destNum: Int) {
        // Atomic check-and-set so a double tap cannot send two packets.
        while (true) {
            val current = _syncTimeStates.value
            if (current[destNum] is SyncTimeState.Sending) return
            if (_syncTimeStates.compareAndSet(current, current + (destNum to SyncTimeState.Sending))) break
        }
        // The app scope lets the ACK wait outlive the screen; the result is still there when the user comes back.
        applicationScope.launch {
            Logger.i { "Syncing time to '$destNum'" }
            val result =
                try {
                    radioController.syncTime(destNum)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(e) { "Time sync to '$destNum' failed to send" }
                    TimeSyncResult.NotSent
                }
            analytics.trackAction("time_sync", mapOf("result" to result.analyticsName()))
            val finished = SyncTimeState.Finished(result)
            _syncTimeStates.update { it + (destNum to finished) }
            delay(SYNC_TIME_RESULT_VISIBLE)
            // A newer attempt may have replaced this result; only clear our own.
            _syncTimeStates.update { states -> if (states[destNum] === finished) states - destNum else states }
        }
    }

    private fun TimeSyncResult.analyticsName(): String = when (this) {
        TimeSyncResult.Synced -> "synced"
        is TimeSyncResult.Nak -> "nak"
        TimeSyncResult.TimedOut -> "timed_out"
        TimeSyncResult.NotSent -> "not_sent"
        is TimeSyncResult.Blocked -> "blocked"
    }

    override suspend fun requestTraceroute(destNum: Int, longName: String) = runRequest {
        Logger.i { "Requesting traceroute for '$destNum'" }
        val packetId = radioController.generatePacketId()
        radioController.requestTraceroute(packetId, destNum)
        analytics.trackAction("traceroute_request")
        _lastTracerouteTime.value = nowMillis
        showFeedback(UiText.Resource(Res.string.requesting_from, Res.string.traceroute, longName))
    }
}
