package com.aasra.cloud

import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/** Plain speech only, not an SSML renderer. Keep the engine-sherpa copy/contract in sync. */
internal object SpeechInput {
    const val MAX_CHARS = 4096
    private const val NAME = """[\p{L}_:][\p{L}\p{N}\p{M}_.:-]*"""
    private val tag = Regex("""</?$NAME(?:\s+$NAME\s*=\s*(?:"[^"]*"|'[^']*'))*\s*/?>""")
    private val unfinishedTag = Regex("""</?(?:speak|prosody|emphasis|break|phoneme|sub|emotion|speed|volume|spell|audio|voice|lang|say-as)(?=\s|/|>|$)|</?$NAME\s+$NAME(?=[\s=>/]|$)|(?<!\S)</?$NAME(?=\s*$)""")
    private val entity = Regex("""&(?!(?:amp|lt|gt|quot|apos|#[0-9]+|#x[0-9a-fA-F]+);)""")
    private val unknownEntity = Regex("""&(?!(?:amp|lt|gt|quot|apos);)$NAME;""")

    fun normalize(input: String): String {
        require(input.length <= MAX_CHARS) { "Speech input exceeds $MAX_CHARS characters" }
        // No declarations, DTDs, processing instructions or CDATA, even before parsing.
        require("<!" !in input && "<?" !in input && !unknownEntity.containsMatchIn(input)) {
            "Unsupported speech declaration or entity"
        }
        val xml = StringBuilder("<root>")
        var offset = 0
        fun appendText(text: String) {
            require(!unfinishedTag.containsMatchIn(text)) { "Incomplete speech markup" }
            xml.append(entity.replace(text, "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
        }
        for (match in tag.findAll(input)) {
            appendText(input.substring(offset, match.range.first))
            xml.append(match.value)
            offset = match.range.last + 1
        }
        appendText(input.substring(offset))
        xml.append("</root>")

        val speech = StringBuilder()
        val handler = object : DefaultHandler() {
            var depth = 0
            var elements = 0
            fun pause(mark: Char) {
                val last = speech.lastOrNull { !it.isWhitespace() }
                if (last != null && last !in ".,;:!?\u0964") speech.append(mark)
                speech.append(' ')
            }
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                if (++depth > 33 || ++elements > 513) throw SAXException("Speech markup limit exceeded")
                when (qName.lowercase()) {
                    "break" -> pause(',')
                    "p", "s" -> speech.append(' ')
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                if (qName.lowercase() in setOf("p", "s")) pause('.')
                depth--
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                speech.append(ch, start, length)
                if (speech.length > MAX_CHARS) throw SAXException("Speech output limit exceeded")
            }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                throw SAXException("External speech entities are disabled")
            override fun error(e: SAXParseException): Unit = throw e
            override fun fatalError(e: SAXParseException): Unit = throw e
        }
        try {
            val reader = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
            }.newSAXParser().xmlReader
            // These SAX features are supported by Android's ExpatReader and the JVM.
            // If a parser cannot disable them, fail closed. No fallback to raw markup.
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.entityResolver = handler
            reader.parse(InputSource(StringReader(xml.toString())))
        } catch (_: Exception) {
            // Do not expose parser diagnostics containing private speech or return partial text.
            throw IllegalArgumentException("Invalid or excessive speech markup")
        }
        var text = speech.toString()
            .replace(Regex("(?m)^[ \\t]*```(?:text|markdown|md|ssml|xml)?[ \\t]*$"), "")
            .replace(Regex("(?m)^[ \\t]*```[ \\t]*"), "")
            .replace(Regex("(?m)^\\s*#{1,6}\\s+"), "")
            .replace(Regex("\\[([^]\\n]+)]\\(https?://[^\\s)]+\\)"), "$1")
        for (marker in listOf("**", "__", "~~", "*", "_", "`")) {
            val escaped = Regex.escape(marker)
            text = text.replace(
                Regex("(?<![\\p{L}\\p{N}])$escaped(?=\\S)(.+?)$escaped(?![\\p{L}\\p{N}])"),
            ) { match ->
                if (match.groupValues[1].last().isWhitespace()) match.value else match.groupValues[1]
            }
        }
        text = text.replace(Regex("[\\s\\p{Z}]+"), " ").trim()
        require(text.length <= MAX_CHARS && "<!" !in text && "<?" !in text &&
            !tag.containsMatchIn(text) && !unfinishedTag.containsMatchIn(text)) {
            "Encoded or excessive speech markup"
        }
        return text
    }
}
