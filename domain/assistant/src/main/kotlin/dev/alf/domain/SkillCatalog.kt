package dev.alf.domain

/**
 * The phrases alf answers to.
 *
 * This is the single source of the offline vocabulary: whatever is enumerable here is what the
 * device can recognise with no network, and it is also what gets synthesised into reference
 * templates for the matcher. Keeping it in a pure Kotlin module means the whole vocabulary is
 * testable without an emulator.
 *
 * Slots marked [SlotKind.RUNTIME] carry no values here — the Android layer fills them from the
 * installed application list and the contact book before the resolver is built.
 */
object SkillCatalog {

    const val WAKE_WORD: String = "hey alf"

    /** How alf answers the wake word. One is picked at random; each has a recorded clip. */
    val WAKE_RESPONSES: List<String> = listOf("Efendim", "Buradayım", "Dinliyorum")

    object Ids {
        const val TIME_NOW = "time_now"
        const val DATE_TODAY = "date_today"
        const val BATTERY_LEVEL = "battery_level"
        const val SET_ALARM = "set_alarm"
        const val SET_TIMER = "set_timer"
        const val SET_VOLUME = "set_volume"
        const val CANCEL = "cancel"
        const val WEATHER_NOW = "weather_now"
        const val WEATHER_TOMORROW = "weather_tomorrow"
        const val NEWS_HEADLINES = "news_headlines"
        const val MARKET_QUOTE = "market_quote"
        const val MARKET_SUMMARY = "market_summary"
        const val TAKE_NOTE = "take_note"
    }

    /** Dative forms, so "alarmı {saat} kur" reads as natural Turkish. */
    private val CLOCK_HOURS: List<SlotValue> = listOf(
        SlotValue("bire", "1"),
        SlotValue("ikiye", "2"),
        SlotValue("üçe", "3"),
        SlotValue("dörde", "4"),
        SlotValue("beşe", "5"),
        SlotValue("altıya", "6"),
        SlotValue("yediye", "7"),
        SlotValue("sekize", "8"),
        SlotValue("dokuza", "9"),
        SlotValue("ona", "10"),
        SlotValue("on bire", "11"),
        SlotValue("on ikiye", "12"),
    )

    /** Durations in minutes. Kept to round numbers people actually say out loud. */
    private val DURATIONS: List<SlotValue> = listOf(
        SlotValue("bir dakika", "1"),
        SlotValue("iki dakika", "2"),
        SlotValue("üç dakika", "3"),
        SlotValue("beş dakika", "5"),
        SlotValue("on dakika", "10"),
        SlotValue("on beş dakika", "15"),
        SlotValue("yirmi dakika", "20"),
        SlotValue("yarım saat", "30"),
        SlotValue("kırk beş dakika", "45"),
        SlotValue("bir saat", "60"),
    )

    /**
     * Feed symbols, spoken the way people actually ask. The value is the symbol the market feed
     * uses, so the executor looks it up directly instead of translating twice.
     */
    private val INSTRUMENTS: List<SlotValue> = listOf(
        SlotValue("dolar", "USDTRY"),
        SlotValue("euro", "EURTRY"),
        SlotValue("sterlin", "GBPTRY"),
        SlotValue("gram altın", "GLDGR"),
        SlotValue("altın", "GLDGR"),
        SlotValue("borsa", "XU100"),
        SlotValue("petrol", "BRENT"),
    )

    private val VOLUME_DIRECTIONS: List<SlotValue> = listOf(
        SlotValue("aç", "up"),
        SlotValue("yükselt", "up"),
        SlotValue("kıs", "down"),
        SlotValue("azalt", "down"),
        SlotValue("kapat", "mute"),
    )

    val definitions: List<SkillDefinition> = listOf(
        SkillDefinition(
            id = Ids.TIME_NOW,
            description = "Şu anki saati söyler.",
            utterances = listOf(
                UtterancePattern("saat kaç"),
                UtterancePattern("saat kaç oldu"),
                UtterancePattern("saati söyle"),
            ),
        ),
        SkillDefinition(
            id = Ids.DATE_TODAY,
            description = "Bugünün tarihini ve gününü söyler.",
            utterances = listOf(
                UtterancePattern("bugün ayın kaçı"),
                UtterancePattern("bugün günlerden ne"),
                UtterancePattern("tarih ne"),
            ),
        ),
        SkillDefinition(
            id = Ids.BATTERY_LEVEL,
            description = "Cihazın pil seviyesini söyler.",
            utterances = listOf(
                UtterancePattern("pil ne kadar"),
                UtterancePattern("pil durumu ne"),
                UtterancePattern("şarj ne kadar"),
            ),
        ),
        SkillDefinition(
            id = Ids.SET_ALARM,
            description = "Belirtilen saate alarm kurar.",
            parameters = listOf(ParamSpec("hour", "Alarmın kurulacağı saat, 1-12 arası.")),
            utterances = listOf(
                UtterancePattern(
                    template = "alarmı {saat} kur",
                    slots = listOf(SlotSpec("saat", param = "hour", values = CLOCK_HOURS)),
                ),
                UtterancePattern(
                    template = "{saat} alarm kur",
                    slots = listOf(SlotSpec("saat", param = "hour", values = CLOCK_HOURS)),
                ),
            ),
        ),
        SkillDefinition(
            id = Ids.SET_TIMER,
            description = "Belirtilen süre sonrası için zamanlayıcı kurar.",
            parameters = listOf(ParamSpec("minutes", "Zamanlayıcı süresi, dakika cinsinden.")),
            utterances = listOf(
                UtterancePattern(
                    template = "{sure} zamanlayıcı kur",
                    slots = listOf(SlotSpec("sure", param = "minutes", values = DURATIONS)),
                ),
                UtterancePattern(
                    template = "{sure} sonra uyar",
                    slots = listOf(SlotSpec("sure", param = "minutes", values = DURATIONS)),
                ),
            ),
        ),
        SkillDefinition(
            id = Ids.SET_VOLUME,
            description = "Medya sesini açar, kısar veya kapatır.",
            parameters = listOf(ParamSpec("direction", "up, down veya mute.")),
            utterances = listOf(
                UtterancePattern(
                    template = "sesi {yon}",
                    slots = listOf(SlotSpec("yon", param = "direction", values = VOLUME_DIRECTIONS)),
                ),
            ),
        ),
        SkillDefinition(
            id = Ids.CANCEL,
            description = "Devam eden işlemi iptal eder ve konuşmayı keser.",
            utterances = listOf(
                UtterancePattern("iptal"),
                UtterancePattern("boş ver"),
                UtterancePattern("vazgeç"),
                UtterancePattern("dur"),
                UtterancePattern("sus"),
            ),
        ),
        SkillDefinition(
            id = Ids.WEATHER_NOW,
            description = "Ayarlanan şehir için güncel hava durumunu söyler.",
            requiresNetwork = true,
            utterances = listOf(
                UtterancePattern("hava nasıl"),
                UtterancePattern("hava durumu ne"),
                UtterancePattern("dışarısı kaç derece"),
            ),
        ),
        SkillDefinition(
            id = Ids.WEATHER_TOMORROW,
            description = "Yarınki hava durumunu söyler.",
            requiresNetwork = true,
            utterances = listOf(
                UtterancePattern("yarın hava nasıl"),
                UtterancePattern("yarın hava durumu ne"),
            ),
        ),
        SkillDefinition(
            id = Ids.NEWS_HEADLINES,
            description = "Son haber başlıklarını okur.",
            requiresNetwork = true,
            utterances = listOf(
                UtterancePattern("haberler"),
                UtterancePattern("haberlerde ne var"),
                UtterancePattern("son haberler"),
            ),
        ),
        SkillDefinition(
            id = Ids.MARKET_QUOTE,
            description = "Bir kur, altın, endeks veya emtia fiyatını söyler.",
            parameters = listOf(ParamSpec("symbol", "Piyasa sembolü: USDTRY, EURTRY, GBPTRY, GLDGR, XU100, BRENT.")),
            requiresNetwork = true,
            utterances = listOf(
                UtterancePattern(
                    template = "{birim} kaç",
                    slots = listOf(SlotSpec("birim", param = "symbol", values = INSTRUMENTS)),
                ),
                UtterancePattern(
                    template = "{birim} kaç oldu",
                    slots = listOf(SlotSpec("birim", param = "symbol", values = INSTRUMENTS)),
                ),
                UtterancePattern(
                    template = "{birim} ne kadar",
                    slots = listOf(SlotSpec("birim", param = "symbol", values = INSTRUMENTS)),
                ),
                UtterancePattern(
                    template = "{birim} ne kadar oldu",
                    slots = listOf(SlotSpec("birim", param = "symbol", values = INSTRUMENTS)),
                ),
                UtterancePattern(
                    template = "{birim} nasıl",
                    slots = listOf(SlotSpec("birim", param = "symbol", values = INSTRUMENTS)),
                ),
            ),
        ),
        SkillDefinition(
            id = Ids.MARKET_SUMMARY,
            description = "Endeks, dolar, euro ve gram altını tek seferde özetler.",
            requiresNetwork = true,
            utterances = listOf(
                UtterancePattern("piyasalar nasıl"),
                UtterancePattern("piyasalar ne durumda"),
                UtterancePattern("piyasa özeti"),
            ),
        ),
        SkillDefinition(
            id = Ids.TAKE_NOTE,
            description = "Verilen metni not olarak kaydeder.",
            parameters = listOf(ParamSpec("text", "Not içeriği.")),
            utterances = listOf(
                UtterancePattern(
                    template = "not al {metin}",
                    slots = listOf(SlotSpec("metin", param = "text", kind = SlotKind.FREE_TEXT)),
                ),
                UtterancePattern(
                    template = "şunu not et {metin}",
                    slots = listOf(SlotSpec("metin", param = "text", kind = SlotKind.FREE_TEXT)),
                ),
            ),
        ),
    )
}
