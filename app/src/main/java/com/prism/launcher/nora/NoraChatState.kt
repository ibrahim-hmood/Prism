package com.prism.launcher.nora

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide state for a generation request, owned by [NoraService].
 *
 * Generation takes tens of seconds for a still and minutes for a clip. Running that in an
 * Activity's lifecycleScope meant navigating away — or rotating the phone — cancelled it
 * mid-flight, which is where the "Something went wrong. Job was cancelled" messages came from.
 *
 * The service writes finished replies straight into [NoraChatStore], so a generation that
 * completes while the conversation is closed still lands in the transcript. The UI only needs
 * to know when to re-read it, which is what [revision] is for.
 */
object NoraChatState {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Live progress line for the in-flight request, shown as a streaming bubble. */
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    /** Bumped whenever the conversation on disk changes. The UI reloads on any change. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun markBusy(initialStatus: String) {
        _status.value = initialStatus
        _busy.value = true
    }

    fun progress(line: String) {
        _status.value = line
    }

    fun markIdle() {
        _busy.value = false
        _status.value = ""
    }

    fun bumpRevision() {
        _revision.value = _revision.value + 1
    }
}
