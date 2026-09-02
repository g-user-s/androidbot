package dev.alf.domain

/** What the user was understood to want. */
data class Intent(
    val skillId: String,
    val params: Map<String, String> = emptyMap(),
    /** 1.0 for an exact phrase match, lower for a fuzzy one. */
    val confidence: Float = 1f,
    val transcript: String = "",
)

/** Turns a transcript into an [Intent], or null when it does not understand. */
fun interface IntentResolver {
    suspend fun resolve(text: String): Intent?
}
