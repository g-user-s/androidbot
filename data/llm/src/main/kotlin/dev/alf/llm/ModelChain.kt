package dev.alf.llm

/**
 * One model endpoint, named exactly as the service names it.
 *
 * Deliberately nothing but the id. Google ships new Flash revisions often, and anything else
 * recorded here — a quota, a tier, a display name — would be a second thing to keep in step with
 * a list the user can edit from the settings screen.
 */
data class GeminiModel(val id: String)

object Models {

    // Declared before DEFAULT_CHAIN: an object's properties initialise in source order, and the
    // chain is parsed at initialisation — reading this after it would read a null.
    private val VALID_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,80}$")

    /**
     * Tried in this order: the newest and most capable first, then the lite variants that carry
     * the large daily allowances. The good models run out early in the day and the cheap ones
     * carry the rest, which is the right way round — an exhausted quota on a free tier is the
     * expected end of that model's day, not a fault.
     *
     * Editable from the settings screen, so a new revision needs no new build.
     */
    val DEFAULT_CHAIN: List<GeminiModel> = parse(
        """
        gemini-3.7-flash
        gemini-3.6-flash
        gemini-3.5-flash
        gemini-3.5-flash-lite
        gemini-3.1-flash-lite
        """,
    )

    /**
     * Reads a user supplied list: one endpoint per line or separated by commas. Blank entries and
     * repeats are dropped, and anything that is not shaped like an endpoint is ignored rather
     * than being sent to the service as a request that cannot succeed.
     */
    fun parse(text: String): List<GeminiModel> =
        text.split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && VALID_ID.matches(it) }
            .distinct()
            .map { GeminiModel(it) }
}

/**
 * Walks the chain, skipping models known to be out of quota.
 *
 * Quotas reset daily, so an exhausted model is set aside until the calendar day changes rather
 * than for a fixed interval — a model exhausted at 23:50 is available again ten minutes later.
 * Time comes in as a day number so the rollover can be tested without waiting for midnight.
 */
class ModelChain(
    private val models: List<GeminiModel> = Models.DEFAULT_CHAIN,
    private val today: () -> Long,
) {
    init {
        require(models.isNotEmpty()) { "the chain needs at least one model" }
    }

    private val exhaustedOn = mutableMapOf<String, Long>()

    /** Models still worth trying, in order. Empty when everything is spent for the day. */
    fun available(): List<GeminiModel> {
        val day = today()
        return models.filter { exhaustedOn[it.id] != day }
    }

    fun markExhausted(model: GeminiModel) {
        exhaustedOn[model.id] = today()
    }

    /** True when every model has reported quota exhaustion today. */
    fun allExhausted(): Boolean = available().isEmpty()
}
