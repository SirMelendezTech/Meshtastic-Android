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

import androidx.compose.runtime.Immutable
import org.meshtastic.core.repository.TimeSyncBlocker
import org.meshtastic.core.repository.TimeSyncResult

/** Progress of the most recent "Sync time" for one node. No entry means idle. */
@Immutable
sealed interface SyncTimeState {
    /** Sent, or about to be, and waiting for the destination's acknowledgement. */
    data object Sending : SyncTimeState

    /** The attempt finished with [result]; shown until it expires or the user retries. */
    data class Finished(val result: TimeSyncResult) : SyncTimeState
}

/** What the node detail screen needs to render the "Sync time" action. */
@Immutable
data class SyncTimeUiState(val blocker: TimeSyncBlocker? = null, val state: SyncTimeState? = null) {
    val canSend: Boolean
        get() = blocker == null && state !is SyncTimeState.Sending
}
