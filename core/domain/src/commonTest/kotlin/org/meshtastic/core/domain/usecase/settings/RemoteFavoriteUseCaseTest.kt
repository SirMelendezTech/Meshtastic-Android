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
package org.meshtastic.core.domain.usecase.settings

import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.repository.AwaitedSendStatus
import org.meshtastic.core.testing.FakeNodeRepository
import org.meshtastic.core.testing.FakeRadioController
import org.meshtastic.proto.SharedContact
import org.meshtastic.proto.User
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteFavoriteUseCaseTest {

    private lateinit var radioController: FakeRadioController
    private lateinit var nodeRepository: FakeNodeRepository
    private lateinit var useCase: RemoteFavoriteUseCase

    private val key = ByteArray(32) { 7 }.toByteString()
    private val remote = node(num = DEST, shortName = "RMT1")

    @BeforeTest
    fun setUp() {
        radioController = FakeRadioController()
        nodeRepository = FakeNodeRepository()
        useCase = RemoteFavoriteUseCase(radioController, nodeRepository)
    }

    @Test
    fun `hex id resolves to node number even when not in local NodeDB`() {
        nodeRepository.setNodes(listOf(remote))

        val result = useCase.resolve("!A1B2C3D4", DEST)

        assertEquals(RemoteFavoriteResolution.Resolved(RemoteFavoriteTarget(0xA1B2C3D4.toInt(), null)), result)
    }

    @Test
    fun `hex id attaches the local node when known`() {
        val known = node(num = 0x0000abcd, shortName = "PR01")
        nodeRepository.setNodes(listOf(remote, known))

        val result = useCase.resolve(" !0000abcd ", DEST)

        assertEquals(RemoteFavoriteResolution.Resolved(RemoteFavoriteTarget(known.num, known)), result)
    }

    @Test
    fun `long decimal resolves to node number`() {
        val result = useCase.resolve("2712847316", DEST)

        assertEquals(RemoteFavoriteResolution.Resolved(RemoteFavoriteTarget(2712847316L.toInt(), null)), result)
    }

    @Test
    fun `short numeric text is treated as a short name`() {
        val numeric = node(num = 55, shortName = "1234")
        nodeRepository.setNodes(listOf(remote, numeric))

        assertEquals(
            RemoteFavoriteResolution.Resolved(RemoteFavoriteTarget(55, numeric)),
            useCase.resolve("1234", DEST),
        )
    }

    @Test
    fun `short name matches case-insensitively ignoring whitespace`() {
        val target = node(num = 10, shortName = "Pr 01")
        nodeRepository.setNodes(listOf(remote, target, node(num = 11, shortName = "PR02")))

        assertEquals(
            RemoteFavoriteResolution.Resolved(RemoteFavoriteTarget(10, target)),
            useCase.resolve("pr01 ", DEST),
        )
    }

    @Test
    fun `short name match is exact and not a prefix`() {
        nodeRepository.setNodes(listOf(remote, node(num = 10, shortName = "PR01")))

        assertEquals(RemoteFavoriteResolution.NotFound("PR0"), useCase.resolve("PR0", DEST))
    }

    @Test
    fun `duplicate short names are ambiguous and sorted by last heard`() {
        val older = node(num = 10, shortName = "PR01", lastHeard = 100)
        val newer = node(num = 11, shortName = "pr01", lastHeard = 200)
        nodeRepository.setNodes(listOf(remote, older, newer))

        val result = useCase.resolve("PR01", DEST)

        assertEquals(
            RemoteFavoriteResolution.Ambiguous(
                listOf(RemoteFavoriteTarget(11, newer), RemoteFavoriteTarget(10, older)),
            ),
            result,
        )
    }

    @Test
    fun `destination itself is rejected by id and by short name`() {
        nodeRepository.setNodes(listOf(remote))

        assertEquals(RemoteFavoriteResolution.IsDestination, useCase.resolve(NodeAddress.numToDefaultId(DEST), DEST))
        assertEquals(RemoteFavoriteResolution.IsDestination, useCase.resolve("rmt1", DEST))
    }

    @Test
    fun `blank input is blank`() {
        assertEquals(RemoteFavoriteResolution.Blank, useCase.resolve("   ", DEST))
    }

    @Test
    fun `favorite sends add_contact then set_favorite to the destination`() = runTest {
        val target = node(num = 10, shortName = "PR01", longName = "Peak One", publicKey = true)
        val steps = mutableListOf<Pair<RemoteFavoriteStep, RemoteFavoriteStepState>>()

        useCase.favorite(DEST, RemoteFavoriteTarget(10, target)) { step, state -> steps += step to state }

        assertEquals(
            listOf(
                DEST to
                    SharedContact.Builder()
                        .also { wb ->
                            wb.node_num = 10
                            wb.user = target.user
                            wb.should_ignore = false
                            wb.manually_verified = false
                        }
                        .build(),
            ),
            radioController.remoteContactCalls,
        )
        assertEquals(listOf(DEST to 10), radioController.remoteFavoriteCalls)
        assertEquals(
            listOf(
                RemoteFavoriteStep.ADD_CONTACT to RemoteFavoriteStepState.Sent,
                RemoteFavoriteStep.ADD_CONTACT to RemoteFavoriteStepState.Acknowledged,
                RemoteFavoriteStep.SET_FAVORITE to RemoteFavoriteStepState.Sent,
                RemoteFavoriteStep.SET_FAVORITE to RemoteFavoriteStepState.Acknowledged,
            ),
            steps,
        )
        assertTrue(radioController.favoritedNodes.isEmpty())
    }

    @Test
    fun `favorite by unknown id skips add_contact`() = runTest {
        val steps = mutableListOf<Pair<RemoteFavoriteStep, RemoteFavoriteStepState>>()

        useCase.favorite(DEST, RemoteFavoriteTarget(99, null)) { step, state -> steps += step to state }

        assertTrue(radioController.remoteContactCalls.isEmpty())
        assertEquals(listOf(DEST to 99), radioController.remoteFavoriteCalls)
        assertEquals(
            RemoteFavoriteStep.ADD_CONTACT to
                RemoteFavoriteStepState.Skipped(RemoteFavoriteSkipReason.NOT_IN_LOCAL_NODE_DB),
            steps.first(),
        )
    }

    @Test
    fun `favorite of keyless local node skips add_contact so the remote key is not erased`() = runTest {
        val steps = mutableListOf<Pair<RemoteFavoriteStep, RemoteFavoriteStepState>>()

        useCase.favorite(DEST, RemoteFavoriteTarget(10, node(num = 10, shortName = "PR01"))) { step, state ->
            steps += step to state
        }

        assertTrue(radioController.remoteContactCalls.isEmpty())
        assertEquals(
            RemoteFavoriteStep.ADD_CONTACT to RemoteFavoriteStepState.Skipped(RemoteFavoriteSkipReason.NO_PUBLIC_KEY),
            steps.first(),
        )
    }

    @Test
    fun `set_favorite is still sent and reported after add_contact fails`() = runTest {
        radioController.addContactOnNodeStatus = AwaitedSendStatus.TIMED_OUT
        radioController.setFavoriteOnNodeStatus = AwaitedSendStatus.RADIO_REJECTED
        val steps = mutableListOf<Pair<RemoteFavoriteStep, RemoteFavoriteStepState>>()
        val target = node(num = 10, shortName = "PR01", publicKey = true)

        useCase.favorite(DEST, RemoteFavoriteTarget(10, target)) { step, state -> steps += step to state }

        assertEquals(listOf(DEST to 10), radioController.remoteFavoriteCalls)
        assertEquals(
            listOf(
                RemoteFavoriteStep.ADD_CONTACT to RemoteFavoriteStepState.Failed(AwaitedSendStatus.TIMED_OUT),
                RemoteFavoriteStep.SET_FAVORITE to RemoteFavoriteStepState.Failed(AwaitedSendStatus.RADIO_REJECTED),
            ),
            steps.filterNot { it.second == RemoteFavoriteStepState.Sent },
        )
    }

    private fun node(
        num: Int,
        shortName: String,
        longName: String = "Node $num",
        lastHeard: Int = 0,
        publicKey: Boolean = false,
    ) = Node(
        num = num,
        lastHeard = lastHeard,
        user =
        User.Builder()
            .also { wb ->
                wb.id = NodeAddress.numToDefaultId(num)
                wb.short_name = shortName
                wb.long_name = longName
                wb.public_key = if (publicKey) key else ByteString.EMPTY
            }
            .build(),
    )

    private companion object {
        const val DEST = 0x12345678
    }
}
