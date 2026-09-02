package dev.alf.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the service is doing, for the settings screen to show. */
object AlfState {

    private val mutable = MutableStateFlow(Phase.Stopped)
    val phase: StateFlow<Phase> = mutable

    private val mutableDetail = MutableStateFlow("")
    val detail: StateFlow<String> = mutableDetail

    enum class Phase { Stopped, Starting, BuildingTemplates, Listening, Awake, Failed }

    internal fun set(phase: Phase, detail: String = "") {
        mutable.value = phase
        mutableDetail.value = detail
    }
}
