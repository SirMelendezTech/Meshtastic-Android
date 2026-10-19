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

import org.koin.core.annotation.Single
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.repository.AdminController
import org.meshtastic.core.repository.AwaitedSendStatus
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.proto.SharedContact

/** A node the user asked to favorite on a remote node, with its local NodeDB entry when the phone knows it. */
data class RemoteFavoriteTarget(val nodeNum: Int, val node: Node?) {
    val nodeId: String
        get() = NodeAddress.numToDefaultId(nodeNum)
}

/** Outcome of resolving the text the user typed into a [RemoteFavoriteTarget]. */
sealed interface RemoteFavoriteResolution {
    data object Blank : RemoteFavoriteResolution

    /** No local node has this short name. */
    data class NotFound(val query: String) : RemoteFavoriteResolution

    /** The text names the node being administered, which cannot favorite itself. */
    data object IsDestination : RemoteFavoriteResolution

    data class Resolved(val target: RemoteFavoriteTarget) : RemoteFavoriteResolution

    /** Several local nodes share the short name; the user must pick one. Sorted most recently heard first. */
    data class Ambiguous(val candidates: List<RemoteFavoriteTarget>) : RemoteFavoriteResolution
}

/** The admin messages sent for one remote favorite, in send order. */
enum class RemoteFavoriteStep {
    ADD_CONTACT,
    SET_FAVORITE,
}

/** Why a [RemoteFavoriteStep] was not sent. */
enum class RemoteFavoriteSkipReason {
    /** The node was given by ID and the phone has no NodeDB entry to share. */
    NOT_IN_LOCAL_NODE_DB,

    /** Firmware stores the contact key unconditionally, so a keyless contact would erase the key the remote holds. */
    NO_PUBLIC_KEY,
}

/** Progress of one [RemoteFavoriteStep]. */
sealed interface RemoteFavoriteStepState {
    data object Pending : RemoteFavoriteStepState

    /** Handed to the radio; waiting for the routing ACK/NAK. */
    data object Sent : RemoteFavoriteStepState

    data object Acknowledged : RemoteFavoriteStepState

    data class Failed(val status: AwaitedSendStatus) : RemoteFavoriteStepState

    data class Skipped(val reason: RemoteFavoriteSkipReason) : RemoteFavoriteStepState
}

/**
 * Marks a node as a favorite in a remote node's NodeDB, so infrastructure nodes can apply the firmware's zero-hop
 * favorite policy to each other.
 *
 * Sends `add_contact` (when the phone knows the node and its key) and then `set_favorite_node` to the destination,
 * waiting for each routing ACK/NAK before the next. `set_favorite_node` is always sent because `add_contact` does not
 * auto-favorite on every role (e.g. CLIENT_BASE). The phone's own favorites are never changed.
 */
@Single
open class RemoteFavoriteUseCase
constructor(
    private val adminController: AdminController,
    private val nodeRepository: NodeRepository,
) {

    /**
     * Resolves [input] to a node: `!xxxxxxxx` or a decimal of more than [MAX_SHORT_NAME_LENGTH] digits is a node
     * number; anything else is matched against local short names, case-insensitively and ignoring whitespace.
     */
    open fun resolve(input: String, destNum: Int): RemoteFavoriteResolution {
        val query = input.filterNot { it.isWhitespace() }
        val nodes = nodeRepository.nodeDBbyNum.value
        val directNum = parseNodeNum(query)
        return when {
            query.isEmpty() -> RemoteFavoriteResolution.Blank
            directNum == destNum -> RemoteFavoriteResolution.IsDestination
            directNum != null -> RemoteFavoriteResolution.Resolved(RemoteFavoriteTarget(directNum, nodes[directNum]))
            else -> resolveShortName(query, input.trim(), nodes, destNum)
        }
    }

    private fun resolveShortName(
        query: String,
        typed: String,
        nodes: Map<Int, Node>,
        destNum: Int,
    ): RemoteFavoriteResolution {
        val matches =
            nodes.values
                .filter { it.num != destNum && it.user.short_name.matchesShortName(query) }
                .sortedByDescending { it.lastHeard }
                .map { RemoteFavoriteTarget(it.num, it) }
        return when {
            matches.size == 1 -> RemoteFavoriteResolution.Resolved(matches.single())
            matches.size > 1 -> RemoteFavoriteResolution.Ambiguous(matches)
            nodes[destNum]?.user?.short_name?.matchesShortName(query) == true -> RemoteFavoriteResolution.IsDestination
            else -> RemoteFavoriteResolution.NotFound(typed)
        }
    }

    private fun String.matchesShortName(query: String): Boolean =
        filterNot(Char::isWhitespace).equals(query, ignoreCase = true)

    /** Sends the admin messages for [target] to [destNum] in order, reporting each step's progress to [onStep]. */
    open suspend fun favorite(
        destNum: Int,
        target: RemoteFavoriteTarget,
        onStep: (RemoteFavoriteStep, RemoteFavoriteStepState) -> Unit,
    ) {
        val contact = target.toSharedContact()
        if (contact == null) {
            val reason =
                if (target.node == null) {
                    RemoteFavoriteSkipReason.NOT_IN_LOCAL_NODE_DB
                } else {
                    RemoteFavoriteSkipReason.NO_PUBLIC_KEY
                }
            onStep(RemoteFavoriteStep.ADD_CONTACT, RemoteFavoriteStepState.Skipped(reason))
        } else {
            onStep(RemoteFavoriteStep.ADD_CONTACT, RemoteFavoriteStepState.Sent)
            onStep(RemoteFavoriteStep.ADD_CONTACT, adminController.addContactOnNode(destNum, contact).toStepState())
        }

        // Sent even if add_contact failed: the remote may already know the node.
        onStep(RemoteFavoriteStep.SET_FAVORITE, RemoteFavoriteStepState.Sent)
        onStep(
            RemoteFavoriteStep.SET_FAVORITE,
            adminController.setFavoriteOnNode(destNum, target.nodeNum).toStepState(),
        )
    }

    private fun RemoteFavoriteTarget.toSharedContact(): SharedContact? = node
        ?.user
        ?.takeIf { it.public_key.size > 0 }
        ?.let { user ->
            SharedContact.Builder()
                .also { wb ->
                    wb.node_num = nodeNum
                    wb.user = user
                    wb.should_ignore = false
                    wb.manually_verified = false
                }
                .build()
        }

    private fun AwaitedSendStatus.toStepState(): RemoteFavoriteStepState = if (this == AwaitedSendStatus.ACCEPTED) {
        RemoteFavoriteStepState.Acknowledged
    } else {
        RemoteFavoriteStepState.Failed(this)
    }

    private companion object {
        /** Firmware short names hold at most four characters, so a longer decimal can only be a node number. */
        const val MAX_SHORT_NAME_LENGTH = 4
        val NODE_ID_PATTERN = Regex("^![0-9a-fA-F]{8}$")
        val DECIMAL_PATTERN = Regex("^[0-9]+$")

        @Suppress("MagicNumber")
        fun parseNodeNum(query: String): Int? = when {
            NODE_ID_PATTERN.matches(query) -> NodeAddress.idToNum(query)

            query.length > MAX_SHORT_NAME_LENGTH && DECIMAL_PATTERN.matches(query) ->
                query.toLongOrNull()?.takeIf { it in 1L..0xFFFFFFFFL }?.toInt()

            else -> null
        }
    }
}
