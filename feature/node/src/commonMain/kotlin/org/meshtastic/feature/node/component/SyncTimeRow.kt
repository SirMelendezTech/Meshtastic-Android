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
package org.meshtastic.feature.node.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.model.getStringResFrom
import org.meshtastic.core.repository.TimeSyncBlocker
import org.meshtastic.core.repository.TimeSyncResult
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.sync_time
import org.meshtastic.core.resources.sync_time_description
import org.meshtastic.core.resources.sync_time_failed
import org.meshtastic.core.resources.sync_time_location_disabled
import org.meshtastic.core.resources.sync_time_no_position
import org.meshtastic.core.resources.sync_time_not_sent
import org.meshtastic.core.resources.sync_time_sending
import org.meshtastic.core.resources.sync_time_synced
import org.meshtastic.core.resources.sync_time_timed_out
import org.meshtastic.core.ui.icon.CheckCircle
import org.meshtastic.core.ui.icon.ErrorOutline
import org.meshtastic.core.ui.icon.History
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Send
import org.meshtastic.core.ui.theme.StatusColors.StatusGreen
import org.meshtastic.feature.node.detail.SyncTimeState
import org.meshtastic.feature.node.detail.SyncTimeUiState

private data class SyncTimeStatus(val text: String, val icon: ImageVector?, val color: Color)

/**
 * "Sync time" row: sends the phone's time to a remote node and shows sending, delivered or failed. A delivered result
 * only confirms the destination acknowledged the packet, which the supporting text says.
 */
@Composable
internal fun SyncTimeRow(syncTime: SyncTimeUiState, onSyncTime: () -> Unit) {
    val title = stringResource(Res.string.sync_time)
    val status = syncTimeStatus(syncTime)
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(
                imageVector = MeshtasticIcons.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Column {
                if (status != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        status.icon?.let { Icon(it, contentDescription = null, tint = status.color) }
                        Text(text = status.text, style = MaterialTheme.typography.bodyMedium, color = status.color)
                    }
                }
                Text(
                    text = stringResource(Res.string.sync_time_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            if (syncTime.state is SyncTimeState.Sending) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                FilledTonalIconButton(onClick = onSyncTime, enabled = syncTime.canSend) {
                    Icon(imageVector = MeshtasticIcons.Send, contentDescription = title)
                }
            }
        },
    )
}

@Composable
private fun syncTimeStatus(syncTime: SyncTimeUiState): SyncTimeStatus? {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val error = MaterialTheme.colorScheme.error
    val state = syncTime.state
    return when {
        state is SyncTimeState.Sending -> SyncTimeStatus(stringResource(Res.string.sync_time_sending), null, neutral)
        state is SyncTimeState.Finished -> finishedStatus(state.result, error)
        syncTime.blocker != null -> blockerText(syncTime.blocker)?.let { SyncTimeStatus(it, null, neutral) }
        else -> null
    }
}

@Composable
private fun finishedStatus(result: TimeSyncResult, error: Color): SyncTimeStatus? = when (result) {
    TimeSyncResult.Synced ->
        SyncTimeStatus(
            stringResource(Res.string.sync_time_synced),
            MeshtasticIcons.CheckCircle,
            MaterialTheme.colorScheme.StatusGreen,
        )

    is TimeSyncResult.Nak ->
        SyncTimeStatus(
            stringResource(Res.string.sync_time_failed, stringResource(getStringResFrom(result.routingError))),
            MeshtasticIcons.ErrorOutline,
            error,
        )

    TimeSyncResult.TimedOut ->
        SyncTimeStatus(stringResource(Res.string.sync_time_timed_out), MeshtasticIcons.ErrorOutline, error)

    TimeSyncResult.NotSent ->
        SyncTimeStatus(stringResource(Res.string.sync_time_not_sent), MeshtasticIcons.ErrorOutline, error)

    is TimeSyncResult.Blocked ->
        blockerText(result.blocker)?.let { SyncTimeStatus(it, MeshtasticIcons.ErrorOutline, error) }
}

@Composable
private fun blockerText(blocker: TimeSyncBlocker): String? = when (blocker) {
    TimeSyncBlocker.OWN_NODE -> null
    TimeSyncBlocker.LOCATION_SHARING_DISABLED -> stringResource(Res.string.sync_time_location_disabled)
    TimeSyncBlocker.NO_VALID_POSITION -> stringResource(Res.string.sync_time_no_position)
}
