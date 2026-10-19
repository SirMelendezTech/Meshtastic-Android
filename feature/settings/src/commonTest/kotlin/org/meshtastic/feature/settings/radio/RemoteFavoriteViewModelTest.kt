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
package org.meshtastic.feature.settings.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.meshtastic.core.domain.usecase.settings.RemoteFavoriteStep
import org.meshtastic.core.domain.usecase.settings.RemoteFavoriteStepState
import org.meshtastic.core.domain.usecase.settings.RemoteFavoriteTarget
import org.meshtastic.core.domain.usecase.settings.RemoteFavoriteUseCase
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.AwaitedSendStatus
import org.meshtastic.core.testing.FakeNodeRepository
import org.meshtastic.core.testing.FakeRadioController
import org.meshtastic.proto.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteFavoriteViewModelTest {

    private val radioController = FakeRadioController()
    private val nodeRepository = FakeNodeRepository()
    private lateinit var viewModel: RemoteFavoriteViewModel

    private val first = node(num = 10, shortName = "PR01", lastHeard = 100)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = RemoteFavoriteViewModel(RemoteFavoriteUseCase(radioController, nodeRepository))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `unknown short name shows not-found error and sends nothing`() {
        viewModel.onInputChange(" zz9 ")
        viewModel.submit(DEST)

        assertEquals(RemoteFavoriteInputError.NotFound("zz9"), viewModel.state.value.error)
        assertNull(viewModel.state.value.pendingConfirmation)
        assertTrue(radioController.remoteFavoriteCalls.isEmpty())
    }

    @Test
    fun `typing clears the error`() {
        viewModel.onInputChange("zz9")
        viewModel.submit(DEST)
        viewModel.onInputChange("zz")

        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `single match asks for confirmation before sending`() {
        nodeRepository.setNodes(listOf(first))

        viewModel.onInputChange("pr01")
        viewModel.submit(DEST)

        assertEquals(RemoteFavoriteTarget(10, first), viewModel.state.value.pendingConfirmation)
        assertTrue(radioController.remoteFavoriteCalls.isEmpty())
    }

    @Test
    fun `duplicates show candidates and choosing one asks for confirmation`() {
        val second = node(num = 11, shortName = "PR01", lastHeard = 200)
        nodeRepository.setNodes(listOf(first, second))

        viewModel.onInputChange("PR01")
        viewModel.submit(DEST)
        assertEquals(listOf(11, 10), viewModel.state.value.candidates.map { it.nodeNum })
        assertEquals("PR01", viewModel.state.value.ambiguousQuery)

        viewModel.chooseCandidate(viewModel.state.value.candidates.last())

        assertTrue(viewModel.state.value.candidates.isEmpty())
        assertEquals(10, viewModel.state.value.pendingConfirmation?.nodeNum)
    }

    @Test
    fun `cancel confirmation sends nothing`() {
        viewModel.onInputChange("!0000000a")
        viewModel.submit(DEST)
        viewModel.cancelConfirmation()
        viewModel.confirm(DEST)

        assertNull(viewModel.state.value.pendingConfirmation)
        assertTrue(radioController.remoteFavoriteCalls.isEmpty())
    }

    @Test
    fun `confirm sends to the destination and reports each step`() {
        radioController.setFavoriteOnNodeStatus = AwaitedSendStatus.TIMED_OUT

        viewModel.onInputChange("!0000000a")
        viewModel.submit(DEST)
        viewModel.confirm(DEST)

        val state = viewModel.state.value
        assertEquals(listOf(DEST to 10), radioController.remoteFavoriteCalls)
        assertEquals(10, state.sentTarget?.nodeNum)
        assertTrue(state.steps[RemoteFavoriteStep.ADD_CONTACT] is RemoteFavoriteStepState.Skipped)
        assertEquals(
            RemoteFavoriteStepState.Failed(AwaitedSendStatus.TIMED_OUT),
            state.steps[RemoteFavoriteStep.SET_FAVORITE],
        )
        assertFalse(state.isSending)
    }

    private fun node(num: Int, shortName: String, lastHeard: Int) = Node(
        num = num,
        lastHeard = lastHeard,
        user =
        User.Builder()
            .also {
                it.short_name = shortName
                it.long_name = "Node $num"
            }
            .build(),
    )

    private companion object {
        const val DEST = 0x12345678
    }
}
