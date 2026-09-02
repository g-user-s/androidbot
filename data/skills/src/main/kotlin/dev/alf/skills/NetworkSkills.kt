package dev.alf.skills

import dev.alf.domain.Skill
import dev.alf.domain.SkillCatalog
import dev.alf.domain.SkillResult
import dev.alf.sources.BigParaParser
import dev.alf.sources.Endpoints
import dev.alf.sources.Instrument
import dev.alf.sources.MarketSpeech
import dev.alf.sources.NewsSpeech
import dev.alf.sources.OpenMeteoParser
import dev.alf.sources.RssParser
import dev.alf.sources.WeatherReport
import dev.alf.sources.WeatherSpeech

/**
 * Skills that answer from a feed.
 *
 * They all fail the same way on purpose: a reachable service that returns something unexpected
 * produces "I could not get that right now", never a made up number. The parsers are built to
 * return nothing rather than a default, and these executors carry that through to what is said.
 */

private const val UNREACHABLE = "Şu an bu bilgiye ulaşamadım."

internal class MarketQuoteSkill(private val http: HttpFetcher) : Skill {
    override val definition = definitionOf(SkillCatalog.Ids.MARKET_QUOTE)

    override suspend fun execute(params: Map<String, String>): SkillResult {
        val symbol = params["symbol"] ?: return SkillResult.Failed("no symbol", "Neyi sorduğunuzu anlayamadım.")
        val instrument = Instrument.ofSymbol(symbol)
            ?: return SkillResult.Failed("unknown symbol $symbol", "Bunu takip etmiyorum.")

        val payload = runCatching { http.get(Endpoints.MARKET) }
            .getOrElse { return SkillResult.Failed(it.toString(), UNREACHABLE) }

        val quote = BigParaParser.quoteOf(payload, instrument)
            ?: return SkillResult.Failed("no quote for $symbol", UNREACHABLE)

        return SkillResult.Spoken(MarketSpeech.quote(instrument, quote))
    }
}

internal class MarketSummarySkill(private val http: HttpFetcher) : Skill {
    override val definition = definitionOf(SkillCatalog.Ids.MARKET_SUMMARY)

    override suspend fun execute(params: Map<String, String>): SkillResult {
        val payload = runCatching { http.get(Endpoints.MARKET) }
            .getOrElse { return SkillResult.Failed(it.toString(), UNREACHABLE) }

        val summary = MarketSpeech.summary(BigParaParser.parse(payload))
            ?: return SkillResult.Failed("empty market payload", UNREACHABLE)

        return SkillResult.Spoken(summary)
    }
}

internal class NewsHeadlinesSkill(private val http: HttpFetcher) : Skill {
    override val definition = definitionOf(SkillCatalog.Ids.NEWS_HEADLINES)

    override suspend fun execute(params: Map<String, String>): SkillResult {
        val feed = runCatching { http.get(Endpoints.NEWS_RSS) }
            .getOrElse { return SkillResult.Failed(it.toString(), UNREACHABLE) }

        val spoken = NewsSpeech.headlines(RssParser.parse(feed))
            ?: return SkillResult.Failed("no items in feed", UNREACHABLE)

        return SkillResult.Spoken(spoken)
    }
}

/**
 * Shared by both weather skills.
 *
 * The city is turned into coordinates once and remembered: this device sits in one house, so
 * repeating the lookup on every question would be a request nobody needs.
 */
internal class WeatherFetcher(private val http: HttpFetcher, private val settings: AlfSettings) {

    suspend fun report(): Result<WeatherReport> = runCatching {
        val (latitude, longitude) = settings.coordinates ?: resolveCity()
        val payload = http.get(Endpoints.forecast(latitude, longitude))
        OpenMeteoParser.parseForecast(payload)
    }

    private suspend fun resolveCity(): Pair<Double, Double> {
        val payload = http.get(Endpoints.geocoding(settings.city))
        val place = OpenMeteoParser.parseGeocoding(payload)
            ?: throw IllegalStateException("could not resolve '${settings.city}'")
        settings.rememberCoordinates(place.latitude, place.longitude)
        return place.latitude to place.longitude
    }
}

internal class WeatherNowSkill(private val weather: WeatherFetcher) : Skill {
    override val definition = definitionOf(SkillCatalog.Ids.WEATHER_NOW)

    override suspend fun execute(params: Map<String, String>): SkillResult {
        val report = weather.report().getOrElse { return SkillResult.Failed(it.toString(), UNREACHABLE) }
        val spoken = WeatherSpeech.now(report)
            ?: return SkillResult.Failed("no current block", UNREACHABLE)
        return SkillResult.Spoken(spoken)
    }
}

internal class WeatherTomorrowSkill(private val weather: WeatherFetcher) : Skill {
    override val definition = definitionOf(SkillCatalog.Ids.WEATHER_TOMORROW)

    override suspend fun execute(params: Map<String, String>): SkillResult {
        val report = weather.report().getOrElse { return SkillResult.Failed(it.toString(), UNREACHABLE) }
        val spoken = WeatherSpeech.tomorrow(report)
            ?: return SkillResult.Failed("no forecast for tomorrow", UNREACHABLE)
        return SkillResult.Spoken(spoken)
    }
}
