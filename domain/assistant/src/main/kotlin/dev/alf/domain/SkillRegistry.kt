package dev.alf.domain

class SkillRegistry(skills: List<Skill>) {
    private val byId: Map<String, Skill> = skills.associateBy { it.definition.id }

    init {
        require(byId.size == skills.size) {
            val duplicates = skills.map { it.definition.id }.groupingBy { it }.eachCount()
                .filterValues { it > 1 }.keys
            "duplicate skill ids: $duplicates"
        }
    }

    val definitions: List<SkillDefinition> = skills.map { it.definition }

    fun find(skillId: String): Skill? = byId[skillId]
}
