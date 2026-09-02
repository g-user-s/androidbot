package dev.alf.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsTest {

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
          <channel>
            <title>BBC News Türkçe</title>
            <item>
              <title>Birinci başlık</title>
              <description>Birinci özet</description>
              <pubDate>Wed, 02 Sep 2026 09:00:00 GMT</pubDate>
            </item>
            <item>
              <title>İkinci başlık</title>
              <description>İkinci özet</description>
            </item>
            <item>
              <title>Üçüncü başlık</title>
            </item>
            <item>
              <title>Dördüncü başlık</title>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `headlines are read in feed order`() {
        val items = RssParser.parse(feed)

        assertEquals(listOf("Birinci başlık", "İkinci başlık", "Üçüncü başlık"), items.map { it.title })
    }

    @Test
    fun `the limit is respected`() {
        assertEquals(1, RssParser.parse(feed, limit = 1).size)
        assertEquals(4, RssParser.parse(feed, limit = 10).size)
    }

    @Test
    fun `an item without a summary is still a headline`() {
        assertNull(RssParser.parse(feed)[2].summary)
        assertEquals("Birinci özet", RssParser.parse(feed)[0].summary)
    }

    @Test
    fun `the channel title is not mistaken for an item`() {
        assertTrue(RssParser.parse(feed).none { it.title == "BBC News Türkçe" })
    }

    @Test
    fun `external entities are not resolved`() {
        // A feed is remote input. A parser left at its defaults would read the referenced file
        // and hand its contents back as a headline.
        val hostile = """
            <?xml version="1.0"?>
            <!DOCTYPE rss [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <rss version="2.0"><channel><item><title>&secret;</title></item></channel></rss>
        """.trimIndent()

        val items = RssParser.parse(hostile)

        assertTrue(items.none { it.title.contains("root") }, "file contents leaked into a headline")
    }

    @Test
    fun `rubbish parses to nothing instead of throwing`() {
        assertTrue(RssParser.parse("not xml").isEmpty())
        assertTrue(RssParser.parse("").isEmpty())
        assertTrue(RssParser.parse("<rss><channel/></rss>").isEmpty())
    }

    @Test
    fun `headlines become one spoken paragraph`() {
        val spoken = assertNotNull(NewsSpeech.headlines(RssParser.parse(feed)))

        assertTrue(spoken.startsWith("Haberlerde şunlar var."), spoken)
        assertTrue("Birinci başlık." in spoken, spoken)
        assertTrue("Üçüncü başlık." in spoken, spoken)
    }

    @Test
    fun `an empty feed produces no sentence`() {
        assertNull(NewsSpeech.headlines(emptyList()))
    }
}
