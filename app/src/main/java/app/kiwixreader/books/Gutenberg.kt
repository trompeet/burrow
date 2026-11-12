package app.kiwixreader.books

/**
 * Project Gutenberg boilerplate: the licence header ends with a line like
 * `*** START OF THE PROJECT GUTENBERG EBOOK PRIDE AND PREJUDICE ***` and the licence footer starts with
 * `*** END OF THE PROJECT GUTENBERG EBOOK PRIDE AND PREJUDICE ***`. Older files say "THIS PROJECT", may omit the
 * space after the asterisks, or wrap the marker over two lines.
 */
object Gutenberg {
    private val startMarker = Regex("""^\s*\*{3}\s*START OF\b.*$""", RegexOption.IGNORE_CASE)
    private val endMarker = Regex("""^\s*\*{3}\s*END OF\b.*$""", RegexOption.IGNORE_CASE)
    private val endOfBookLine = Regex("""^\s*End of (the )?Project Gutenberg\b.*$""", RegexOption.IGNORE_CASE)

    fun isStartMarker(line: String) = startMarker.matches(line)
    fun isEndMarker(line: String) = endMarker.matches(line)

    /** Returns [text] without the Gutenberg header and footer. Text without markers is returned unchanged. */
    fun strip(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var start = 0
        var end = lines.size

        val startIndex = lines.indexOfFirst(::isStartMarker)
        if (startIndex >= 0) {
            var markerEnd = startIndex
            // A wrapped marker ends with "***" on one of the next lines.
            while (!lines[markerEnd].trimEnd().endsWith("***") && markerEnd < startIndex + 3 && markerEnd + 1 < lines.size) {
                markerEnd++
            }
            if (!lines[markerEnd].trimEnd().endsWith("***")) markerEnd = startIndex
            start = markerEnd + 1
        }

        val endIndex = (start until lines.size).firstOrNull { isEndMarker(lines[it]) }
        if (endIndex != null) {
            end = endIndex
            // "End of the Project Gutenberg EBook of ..." usually sits just above the END marker.
            val lastText = (end - 1 downTo start).firstOrNull { lines[it].isNotBlank() }
            if (lastText != null && endOfBookLine.matches(lines[lastText]) && end - lastText <= 4) end = lastText
        }

        if (startIndex < 0 && endIndex == null) return text
        return lines.subList(start, end).joinToString("\n").trim('\n', ' ', '\t')
    }
}
