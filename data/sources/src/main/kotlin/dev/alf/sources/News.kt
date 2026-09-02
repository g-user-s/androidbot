package dev.alf.sources

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class NewsItem(val title: String, val summary: String?)

/**
 * Reads an RSS feed down to its headlines.
 *
 * External entity resolution is switched off. The document comes from the network, and an XML
 * parser left at its defaults will happily follow an entity reference to a local file or an
 * internal address on the reader's behalf — a well known way to turn a feed reader into a probe
 * of the machine it runs on. Nothing here needs entities, so they are simply refused.
 */
object RssParser {

    fun parse(xml: String, limit: Int = 3): List<NewsItem> {
        if (xml.isBlank()) return emptyList()

        val document = runCatching {
            documentBuilderFactory().newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }.getOrNull() ?: return emptyList()

        val items = document.getElementsByTagName("item")
        if (items.length == 0) return emptyList()

        return (0 until items.length)
            .asSequence()
            .mapNotNull { items.item(it) as? Element }
            .mapNotNull { item ->
                val title = text(item, "title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                NewsItem(title, text(item, "description")?.takeIf { it.isNotBlank() })
            }
            .take(limit)
            .toList()
    }

    private fun documentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            isExpandEntityReferences = false
            isNamespaceAware = false
        }

    /** Direct children only: an `item` may itself contain nested elements with the same names. */
    private fun text(item: Element, tag: String): String? {
        var child = item.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName.equals(tag, ignoreCase = true)) {
                return child.textContent?.trim()
            }
            child = child.nextSibling
        }
        return null
    }
}

/** Headlines as one spoken paragraph. */
object NewsSpeech {

    fun headlines(items: List<NewsItem>, source: String = "Haberlerde"): String? {
        if (items.isEmpty()) return null
        val sentences = items.joinToString(" ") { item ->
            val title = item.title.trimEnd('.', '!', '?', ' ')
            "$title."
        }
        return "$source şunlar var. $sentences"
    }
}
