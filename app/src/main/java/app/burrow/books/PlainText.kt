package app.burrow.books

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Plain-text book helpers: decoding, reflowing hard-wrapped lines, and finding chapter headings. */
object PlainText {
    class Paragraph(val text: String, val isHeading: Boolean)

    /** UTF-8 (with or without BOM); falls back to Windows-1252 for older Gutenberg files. */
    fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
        return text.removePrefix("﻿")
    }

    private val heading = Regex(
        """^(CHAPTER|Chapter|BOOK|Book|PART|Part|ACT|Act|STAVE|Stave|LETTER|Letter|VOLUME|Volume)\s+([0-9]+|[IVXLCDM]+|[A-Za-z-]+)\b.{0,60}$""",
    )

    fun isHeading(paragraph: String) = paragraph.length <= 80 && heading.matches(paragraph)

    /**
     * Splits text into paragraphs at blank lines and joins the hard-wrapped lines inside each paragraph.
     * Blocks of short lines (verse, addresses) and indented blocks (contents lists) keep their line breaks.
     * A block whose first line is a chapter heading ("CHAPTER I." plus an optional title line) is a heading.
     */
    fun paragraphs(text: String): List<Paragraph> =
        text.replace("\r\n", "\n").replace('\r', '\n')
            .split(Regex("""\n[ \t]*\n"""))
            .map { block -> block.lines().filter { it.isNotBlank() } }
            .filter { it.isNotEmpty() }
            .map { raw ->
                val lines = raw.map { it.trim().replace(Regex("""\s{2,}"""), " ") }
                if (lines.size <= 3 && isHeading(lines[0])) return@map Paragraph(lines.joinToString("\n"), true)
                val indented = raw.size > 1 && raw.all { it.startsWith(" ") || it.startsWith("\t") }
                val keepBreaks = raw.size > 1 && (indented || lines.all { it.length < SHORT_LINE })
                Paragraph(if (keepBreaks) lines.joinToString("\n") else lines.joinToString(" "), false)
            }

    private const val SHORT_LINE = 45
}
