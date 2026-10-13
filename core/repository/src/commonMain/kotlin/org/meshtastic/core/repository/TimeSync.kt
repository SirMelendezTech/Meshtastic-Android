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
package org.meshtastic.core.repository

/**
 * A routing ACK/NAK as decoded from a `ROUTING_APP` packet: [requestId] is the ID of the packet it answers, [from] the
 * node that sent it, and [errorReason] the raw `Routing.Error` value (0 = NONE, i.e. an ACK).
 */
data class RoutingResult(val requestId: Int, val from: Int, val errorReason: Int) {
    val isAck: Boolean
        get() = errorReason == 0
}

/** Why a time sync cannot be sent to a node right now. */
enum class TimeSyncBlocker {
    /** The destination is our own node. */
    OWN_NODE,

    /** The user has turned off sharing our node's location, and a time sync must carry it. */
    LOCATION_SHARING_DISABLED,

    /** Our node has no valid position to attach; sending 0,0 would overwrite our own position. */
    NO_VALID_POSITION,
}

/**
 * Outcome of a time sync. [Synced] only proves that the destination acknowledged delivery; the firmware does not report
 * whether it applied the time.
 */
sealed interface TimeSyncResult {
    /** The destination itself acknowledged the packet. */
    data object Synced : TimeSyncResult

    /** A node answered with a routing error; [routingError] is the raw `Routing.Error` value. */
    data class Nak(val routingError: Int) : TimeSyncResult

    /** No acknowledgement from the destination arrived within the response window. */
    data object TimedOut : TimeSyncResult

    /** The packet never reached the radio (disconnected or rejected by the outbound queue). */
    data object NotSent : TimeSyncResult

    /** Nothing was sent because of [blocker]. */
    data class Blocked(val blocker: TimeSyncBlocker) : TimeSyncResult
}
