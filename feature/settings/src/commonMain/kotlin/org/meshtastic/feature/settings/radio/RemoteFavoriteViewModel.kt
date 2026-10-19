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

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.domain.usecase.settings.RemoteFavoriteResolution
import org.meshtastic.core.domain.usecase.settings.RemoteFavoriteStep
import org.meshtastic.core.domain.usecase.settings.RemoteFavoriteStepState
import org.meshtastic.core.domain.usecase.settings.RemoteFavoriteTarget
import org.meshtastic.core.domain.usecase.settings.RemoteFavoriteUseCase
import org.meshtastic.core.repository.AwaitedSendStatus
import org.meshtastic.core.ui.viewmodel.safeLaunch

/** Why the typed text could not be turned into a node. */
sealed interface RemoteFavoriteInputError {
    data class NotFound(val query: String) : RemoteFavoriteInputError

    data object IsDestination : RemoteFavoriteInputError
}

data class RemoteFavoriteUiState(
    val input: String = "",
    val error: RemoteFavoriteInputError? = null,
    /** Non-empty only while the user must pick between nodes sharing [ambiguousQuery]. */
    val candidates: List<RemoteFavoriteTarget> = emptyList(),
    val ambiguousQuery: String = "",
    /** The node awaiting the user's one-line confirmation. */
    val pendingConfirmation: RemoteFavoriteTarget? = null,
    /** The node of the current or last send; [steps] describes its progress. */
    val sentTarget: RemoteFavoriteTarget? = null,
    val steps: Map<RemoteFavoriteStep, RemoteFavoriteStepState> = emptyMap(),
) {
    val isSending: Boolean
        get() =
            sentTarget != null &&
                RemoteFavoriteStep.entries.any {
                    val state = steps[it]
                    state == null || state == RemoteFavoriteStepState.Pending || state == RemoteFavoriteStepState.Sent
                }
}

/** Drives the "favorite a node on this remote" action of the remote Administration screen. */
@KoinViewModel
class RemoteFavoriteViewModel(private val remoteFavoriteUseCase: RemoteFavoriteUseCase) : ViewModel() {

    private val _state = MutableStateFlow(RemoteFavoriteUiState())
    val state: StateFlow<RemoteFavoriteUiState> = _state.asStateFlow()

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text, error = null) }
    }

    /** Resolves the typed text against [destNum], then asks for confirmation or a choice between duplicates. */
    fun submit(destNum: Int) {
        if (_state.value.isSending) return
        when (val result = remoteFavoriteUseCase.resolve(_state.value.input, destNum)) {
            RemoteFavoriteResolution.Blank -> Unit

            is RemoteFavoriteResolution.NotFound ->
                _state.update { it.copy(error = RemoteFavoriteInputError.NotFound(result.query)) }

            RemoteFavoriteResolution.IsDestination ->
                _state.update { it.copy(error = RemoteFavoriteInputError.IsDestination) }

            is RemoteFavoriteResolution.Resolved -> _state.update { it.copy(pendingConfirmation = result.target) }

            is RemoteFavoriteResolution.Ambiguous ->
                _state.update { it.copy(candidates = result.candidates, ambiguousQuery = it.input.trim()) }
        }
    }

    fun chooseCandidate(target: RemoteFavoriteTarget) {
        _state.update { it.copy(candidates = emptyList(), pendingConfirmation = target) }
    }

    fun dismissCandidates() {
        _state.update { it.copy(candidates = emptyList()) }
    }

    fun cancelConfirmation() {
        _state.update { it.copy(pendingConfirmation = null) }
    }

    /** Sends the confirmed node to [destNum], one admin message at a time. */
    fun confirm(destNum: Int) {
        val target = _state.value.pendingConfirmation ?: return
        if (_state.value.isSending) return
        _state.update {
            it.copy(
                pendingConfirmation = null,
                sentTarget = target,
                steps = RemoteFavoriteStep.entries.associateWith { RemoteFavoriteStepState.Pending },
            )
        }
        safeLaunch(tag = "remoteFavorite") {
            try {
                remoteFavoriteUseCase.favorite(destNum, target) { step, stepState ->
                    _state.update { it.copy(steps = it.steps + (step to stepState)) }
                }
            } finally {
                // An unexpected failure must not leave the form locked in the sending state.
                _state.update { it.copy(steps = it.steps.mapValues { (_, s) -> s.failIfUnfinished() }) }
            }
        }
    }

    private fun RemoteFavoriteStepState.failIfUnfinished(): RemoteFavoriteStepState =
        if (this == RemoteFavoriteStepState.Pending || this == RemoteFavoriteStepState.Sent) {
            RemoteFavoriteStepState.Failed(AwaitedSendStatus.SEND_FAILED)
        } else {
            this
        }
}
