package dev.alf.domain

/**
 * One way of asking for a skill, optionally with slots: `"alarmı {saat} kur"`.
 *
 * Offline recognition matches speech against a fixed list of phrases, so a phrasing only
 * survives without a network if every slot in it can be enumerated — see [SlotKind] and
 * [expand]. Slot values carry the spoken form and the value separately, which is what lets
 * Turkish inflection ("yediye") stay in the phrase while the skill still receives a plain "7".
 */
data class UtterancePattern(
    val template: String,
    val slots: List<SlotSpec> = emptyList(),
) {
    init {
        require(template.isNotBlank()) { "utterance template must not be blank" }
        val declared = SLOT_RE.findAll(template).map { it.groupValues[1] }.toList()
        require(declared.size == declared.toSet().size) { "template '$template' repeats a slot name" }
        require(declared.toSet() == slots.map { it.name }.toSet()) {
            "template '$template' declares slots ${declared.toSet()} but was given ${slots.map { it.name }}"
        }
    }

    /** True when this phrasing can be rendered as a finite phrase list, i.e. recognised offline. */
    val enumerable: Boolean = slots.none { it.kind == SlotKind.FREE_TEXT }

    /**
     * Every concrete phrasing, paired with the parameters that phrasing implies. A pattern with
     * no slots expands to itself; one holding free text expands to nothing, because there is no
     * finite set of phrases to enumerate.
     */
    fun expand(): List<ExpandedUtterance> {
        if (!enumerable) return emptyList()
        return slots.fold(listOf(ExpandedUtterance(template, emptyMap()))) { acc, slot ->
            acc.flatMap { partial ->
                slot.values.map { value ->
                    ExpandedUtterance(
                        phrase = partial.phrase.replace("{${slot.name}}", value.spoken),
                        params = partial.params + (slot.param to value.value),
                    )
                }
            }
        }
    }

    /** Substitutes runtime discovered values for [slotName], leaving the rest of the pattern alone. */
    fun withSlotValues(slotName: String, values: List<SlotValue>): UtterancePattern {
        require(slots.any { it.name == slotName }) { "template '$template' has no slot '$slotName'" }
        return copy(slots = slots.map { if (it.name == slotName) it.copy(values = values) else it })
    }

    companion object {
        // Escape both braces explicitly. The desktop JDK accepts a bare closing brace here,
        // while Android 10's regex engine rejects it during class initialisation.
        private val SLOT_RE = Regex("""\{(\w+)\}""")
    }
}

enum class SlotKind {
    /** Values are known up front and live in the catalog. */
    ENUMERATED,

    /** Values exist only on the device — installed apps, contacts — and are filled in at startup. */
    RUNTIME,

    /** Arbitrary user text. Not enumerable, so these phrasings need a cloud recogniser. */
    FREE_TEXT,
}

data class SlotSpec(
    val name: String,
    val param: String,
    val kind: SlotKind = SlotKind.ENUMERATED,
    val values: List<SlotValue> = emptyList(),
) {
    init {
        require(kind != SlotKind.FREE_TEXT || values.isEmpty()) {
            "free text slot '$name' cannot carry values"
        }
        require(kind != SlotKind.ENUMERATED || values.isNotEmpty()) {
            "enumerated slot '$name' needs values; mark it RUNTIME if they arrive on the device"
        }
    }
}

/** [spoken] goes into the phrase, [value] goes to the skill. */
data class SlotValue(val spoken: String, val value: String)

data class ExpandedUtterance(val phrase: String, val params: Map<String, String>)
