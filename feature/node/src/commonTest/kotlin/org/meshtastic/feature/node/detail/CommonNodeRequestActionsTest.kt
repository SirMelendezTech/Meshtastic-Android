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

import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.repository.LocalNodeUnavailableException
import org.meshtastic.core.repository.PacketQueueRejectedException
import org.meshtastic.core.repository.PlatformAnalytics
import org.meshtastic.core.repository.RadioController
import org.meshtastic.core.repository.TimeSyncBlocker
import org.meshtastic.core.repository.TimeSyncResult
import org.meshtastic.core.testing.FakeApplicationCoroutineScope
import org.meshtastic.core.testing.FakeRadioController
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class CommonNodeRequestActionsTest {
    private val radioController: RadioController = mock()
    private val snackbarManager = RecordingSnackbarManager()
    private val actions =
        CommonNodeRequestActions(
            radioController = radioController,
            snackbarManager = snackbarManager,
            analytics = mock<PlatformAnalytics>(),
            applicationScope = FakeApplicationCoroutineScope(UnconfinedTestDispatcher()),
            resolveUiText = resolveNodeRequestFailureUiText,
        )

    @BeforeTest
    fun setUp() {
        snackbarManager.messages.clear()
    }

    @Test
    fun `missing local identity uses the same localized request feedback`() = runTest {
        everySuspend { radioController.requestUserInfo(1234) } throws LocalNodeUnavailableException("local node")

        actions.requestUserInfo(destNum = 1234, longName = "Test Node")

        assertEquals(listOf(NODE_REQUEST_SEND_FAILED_TEXT), snackbarManager.messages)
    }

    @Test
    fun `traceroute queue rejection is surfaced without starting the request timer`() = runTest {
        val rejection = PacketQueueRejectedException("Traceroute request")
        every { radioController.generatePacketId() } returns 42
        everySuspend { radioController.requestTraceroute(42, 1234) } throws rejection

        actions.requestTraceroute(destNum = 1234, longName = "Test Node")

        assertNull(actions.lastTracerouteTime.value)
        assertEquals(listOf(NODE_REQUEST_SEND_FAILED_TEXT), snackbarManager.messages)
    }

    @Test
    fun `neighbor queue rejection is surfaced without starting the request timer`() = runTest {
        val rejection = PacketQueueRejectedException("Neighbor info request")
        every { radioController.generatePacketId() } returns 43
        everySuspend { radioController.requestNeighborInfo(43, 1234) } throws rejection

        actions.requestNeighborInfo(destNum = 1234, longName = "Test Node")

        assertEquals(emptyMap(), actions.lastRequestNeighborTimes.value)
        assertEquals(listOf(NODE_REQUEST_SEND_FAILED_TEXT), snackbarManager.messages)
    }

    private val fakeRadio = FakeRadioController()

    private fun TestScope.syncTimeActions() = CommonNodeRequestActions(
        radioController = fakeRadio,
        snackbarManager = snackbarManager,
        analytics = mock<PlatformAnalytics>(MockMode.autofill),
        applicationScope = FakeApplicationCoroutineScope(StandardTestDispatcher(testScheduler)),
        resolveUiText = resolveNodeRequestFailureUiText,
    )

    @Test
    fun `sync time is sending until the result arrives and then shows it`() = runTest {
        val actions = syncTimeActions()
        fakeRadio.syncTimeGate = CompletableDeferred()

        actions.syncTime(1234)
        runCurrent()

        assertEquals(SyncTimeState.Sending, actions.syncTimeStates.value[1234])

        fakeRadio.syncTimeGate?.complete(Unit)
        runCurrent()

        assertEquals(SyncTimeState.Finished(TimeSyncResult.Synced), actions.syncTimeStates.value[1234])
    }

    @Test
    fun `sync time ignores a second tap while one is in flight`() = runTest {
        val actions = syncTimeActions()
        fakeRadio.syncTimeGate = CompletableDeferred()

        actions.syncTime(1234)
        actions.syncTime(1234)
        runCurrent()

        assertEquals(listOf(1234), fakeRadio.syncTimeCalls)
    }

    @Test
    fun `sync time failure stays visible for a while and then clears`() = runTest {
        val actions = syncTimeActions()
        fakeRadio.syncTimeResult = TimeSyncResult.Nak(routingError = 5)

        actions.syncTime(1234)
        runCurrent()

        assertEquals(SyncTimeState.Finished(TimeSyncResult.Nak(5)), actions.syncTimeStates.value[1234])

        advanceTimeBy(31.seconds)

        assertNull(actions.syncTimeStates.value[1234])
    }

    @Test
    fun `sync time reports a blocker without sending`() = runTest {
        val actions = syncTimeActions()
        fakeRadio.syncTimeBlockerResult = TimeSyncBlocker.NO_VALID_POSITION

        actions.syncTime(1234)
        runCurrent()

        assertEquals(
            SyncTimeState.Finished(TimeSyncResult.Blocked(TimeSyncBlocker.NO_VALID_POSITION)),
            actions.syncTimeStates.value[1234],
        )
    }

    @Test
    fun `retrying after a result starts a new attempt`() = runTest {
        val actions = syncTimeActions()
        fakeRadio.syncTimeResult = TimeSyncResult.TimedOut
        actions.syncTime(1234)
        runCurrent()

        advanceTimeBy(15.seconds)
        fakeRadio.syncTimeResult = TimeSyncResult.Synced
        actions.syncTime(1234)
        runCurrent()
        advanceTimeBy(16.seconds) // past the first attempt's expiry, which must not clear the second result

        assertEquals(listOf(1234, 1234), fakeRadio.syncTimeCalls)
        assertEquals(SyncTimeState.Finished(TimeSyncResult.Synced), actions.syncTimeStates.value[1234])
    }
}
