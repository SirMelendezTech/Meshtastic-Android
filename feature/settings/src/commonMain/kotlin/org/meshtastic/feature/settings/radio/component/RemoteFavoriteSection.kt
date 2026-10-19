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
package org.meshtastic.feature.settings.radio.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.domain.usecase.settings.RemoteFavoriteSkipReason
import org.meshtastic.core.domain.usecase.settings.RemoteFavoriteStep
import org.meshtastic.core.domain.usecase.settings.RemoteFavoriteStepState
import org.meshtastic.core.domain.usecase.settings.RemoteFavoriteTarget
import org.meshtastic.core.repository.AwaitedSendStatus
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.remote_favorite_action
import org.meshtastic.core.resources.remote_favorite_choose_title
import org.meshtastic.core.resources.remote_favorite_confirm
import org.meshtastic.core.resources.remote_favorite_description
import org.meshtastic.core.resources.remote_favorite_error_nak
import org.meshtastic.core.resources.remote_favorite_error_not_sent
import org.meshtastic.core.resources.remote_favorite_error_timeout
import org.meshtastic.core.resources.remote_favorite_input_label
import org.meshtastic.core.resources.remote_favorite_is_destination
import org.meshtastic.core.resources.remote_favorite_not_found
import org.meshtastic.core.resources.remote_favorite_skipped_no_key
import org.meshtastic.core.resources.remote_favorite_skipped_unknown
import org.meshtastic.core.resources.remote_favorite_state_ack
import org.meshtastic.core.resources.remote_favorite_state_error
import org.meshtastic.core.resources.remote_favorite_state_pending
import org.meshtastic.core.resources.remote_favorite_state_sent
import org.meshtastic.core.resources.remote_favorite_step_add_contact
import org.meshtastic.core.resources.remote_favorite_step_set_favorite
import org.meshtastic.core.resources.remote_favorite_title
import org.meshtastic.core.ui.component.MeshtasticDialog
import org.meshtastic.core.ui.icon.Favorite
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.util.formatAgo
import org.meshtastic.feature.settings.component.ExpressiveSection
import org.meshtastic.feature.settings.radio.RemoteFavoriteInputError
import org.meshtastic.feature.settings.radio.RemoteFavoriteUiState

/** One-line label for a node: `PR01 – Long Name (!a1b2c3d4)`, or just the ID when the phone does not know it. */
fun RemoteFavoriteTarget.displayLabel(): String {
    val user = node?.user ?: return nodeId
    return "${user.short_name} – ${user.long_name} ($nodeId)"
}

/**
 * Text field that favorites a typed node on the remote being administered, with the duplicate-name chooser, the
 * confirmation and per-message progress.
 */
@Suppress("LongParameterList")
@Composable
fun RemoteFavoriteSection(
    state: RemoteFavoriteUiState,
    remoteName: String,
    enabled: Boolean,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onChooseCandidate: (RemoteFavoriteTarget) -> Unit,
    onDismissCandidates: () -> Unit,
    onConfirm: () -> Unit,
    onCancelConfirmation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSubmit = enabled && !state.isSending && state.input.isNotBlank()

    ExpressiveSection(title = stringResource(Res.string.remote_favorite_title), modifier = modifier) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.remote_favorite_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !state.isSending,
                singleLine = true,
                label = { Text(stringResource(Res.string.remote_favorite_input_label)) },
                isError = state.error != null,
                supportingText = state.error?.let { error -> { Text(error.message()) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSubmit) onSubmit() }),
            )
            Button(onClick = onSubmit, enabled = canSubmit, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(Res.string.remote_favorite_action))
            }
            state.sentTarget?.let { target -> RemoteFavoriteProgress(target, state.steps) }
        }
    }

    if (state.candidates.isNotEmpty()) {
        CandidatesDialog(
            query = state.ambiguousQuery,
            candidates = state.candidates,
            onChoose = onChooseCandidate,
            onDismiss = onDismissCandidates,
        )
    }

    state.pendingConfirmation?.let { target ->
        MeshtasticDialog(
            icon = MeshtasticIcons.Favorite,
            message = stringResource(Res.string.remote_favorite_confirm, target.displayLabel(), remoteName),
            confirmText = stringResource(Res.string.remote_favorite_action),
            onConfirm = onConfirm,
            onDismiss = onCancelConfirmation,
        )
    }
}

@Composable
private fun RemoteFavoriteProgress(
    target: RemoteFavoriteTarget,
    steps: Map<RemoteFavoriteStep, RemoteFavoriteStepState>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = target.displayLabel(), style = MaterialTheme.typography.titleSmall)
        RemoteFavoriteStep.entries.forEach { step ->
            val stepState = steps[step] ?: RemoteFavoriteStepState.Pending
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = stringResource(step.label()), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stepState.text(),
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                    when (stepState) {
                        is RemoteFavoriteStepState.Failed -> MaterialTheme.colorScheme.error
                        RemoteFavoriteStepState.Acknowledged -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun CandidatesDialog(
    query: String,
    candidates: List<RemoteFavoriteTarget>,
    onChoose: (RemoteFavoriteTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    MeshtasticDialog(
        title = stringResource(Res.string.remote_favorite_choose_title, query),
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                candidates.forEach { candidate ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable { onChoose(candidate) }.padding(vertical = 8.dp),
                    ) {
                        Text(text = candidate.displayLabel(), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = formatAgo(candidate.node?.lastHeard ?: 0),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun RemoteFavoriteInputError.message(): String = when (this) {
    is RemoteFavoriteInputError.NotFound -> stringResource(Res.string.remote_favorite_not_found, query)
    RemoteFavoriteInputError.IsDestination -> stringResource(Res.string.remote_favorite_is_destination)
}

private fun RemoteFavoriteStep.label() = when (this) {
    RemoteFavoriteStep.ADD_CONTACT -> Res.string.remote_favorite_step_add_contact
    RemoteFavoriteStep.SET_FAVORITE -> Res.string.remote_favorite_step_set_favorite
}

@Composable
private fun RemoteFavoriteStepState.text(): String = when (this) {
    RemoteFavoriteStepState.Pending -> stringResource(Res.string.remote_favorite_state_pending)

    RemoteFavoriteStepState.Sent -> stringResource(Res.string.remote_favorite_state_sent)

    RemoteFavoriteStepState.Acknowledged -> stringResource(Res.string.remote_favorite_state_ack)

    is RemoteFavoriteStepState.Failed -> stringResource(Res.string.remote_favorite_state_error, status.reason())

    is RemoteFavoriteStepState.Skipped ->
        when (reason) {
            RemoteFavoriteSkipReason.NOT_IN_LOCAL_NODE_DB ->
                stringResource(Res.string.remote_favorite_skipped_unknown)

            RemoteFavoriteSkipReason.NO_PUBLIC_KEY -> stringResource(Res.string.remote_favorite_skipped_no_key)
        }
}

@Composable
private fun AwaitedSendStatus.reason(): String = when (this) {
    AwaitedSendStatus.RADIO_REJECTED -> stringResource(Res.string.remote_favorite_error_nak)

    AwaitedSendStatus.TIMED_OUT -> stringResource(Res.string.remote_favorite_error_timeout)

    AwaitedSendStatus.ACCEPTED,
    AwaitedSendStatus.REJECTED,
    AwaitedSendStatus.TRANSPORT_STOPPED,
    AwaitedSendStatus.SEND_FAILED,
    -> stringResource(Res.string.remote_favorite_error_not_sent)
}
