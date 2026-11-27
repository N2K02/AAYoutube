package com.aayoutube.app

import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.w3c.dom.Element

/** Accept only a bounded static manifest containing a single audio representation. */
internal object SabrManifestPolicy {
    fun valid(xml: String, mime: String, codecs: String): Boolean {
        if (xml.length !in 1..120000 || !mime.startsWith("audio/") ||
            xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY", true)) return false
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isExpandEntityReferences = false
            val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            val root = doc.documentElement
            fun elements(name: String): List<Element> {
                val nodes = root.getElementsByTagNameNS("*", name)
                return (0 until nodes.length).map { nodes.item(it) as Element }
            }
            if (root.localName != "MPD" || root.getAttribute("type") != "static") return false
            if (elements("Period").size != 1 || elements("AdaptationSet").size != 1 ||
                elements("Representation").size != 1 || elements("BaseURL").size != 1 ||
                elements("SegmentBase").size != 1 || elements("Initialization").size != 1 ||
                elements("ContentProtection").isNotEmpty()) return false
            val permitted = setOf("MPD", "Period", "AdaptationSet", "Representation", "BaseURL",
                "SegmentBase", "Initialization", "SupplementalProperty", "AudioChannelConfiguration", "Role")
            val all = root.getElementsByTagName("*")
            for (i in 0 until all.length) {
                val e = all.item(i) as Element
                if (e.localName !in permitted) return false
                for (j in 0 until e.attributes.length) {
                    val a = e.attributes.item(j)
                    if (a.localName in setOf("href", "sourceURL", "index", "media", "initialization", "base")) return false
                }
            }
            val adaptation = elements("AdaptationSet").single()
            val representation = elements("Representation").single()
            if (adaptation.getAttribute("contentType") != "audio" ||
                adaptation.getAttribute("mimeType") != mime ||
                representation.getAttribute("codecs") != codecs) return false
            val base = URI(elements("BaseURL").single().textContent.trim())
            if (base.scheme != "sabr" || base.host != "audio" || base.userInfo != null ||
                base.fragment != null || base.rawQuery?.startsWith("key=") != true) return false
            elements("SupplementalProperty").count {
                it.getAttribute("schemeIdUri") == "urn:youtube:sabr" && it.getAttribute("value").isNotBlank()
            } == 1
        }.getOrDefault(false)
    }
}
