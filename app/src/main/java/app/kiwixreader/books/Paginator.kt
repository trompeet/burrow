package app.kiwixreader.books

/**
 * Pagination arithmetic, independent of Android so it can be unit tested. Text is laid out once per section
 * (a StaticLayout); a page is a run of whole lines that fits the page height.
 */
object Paginator {
    /**
     * Returns the index of the first line of every page. [lineTops]/[lineBottoms] are the layout's line
     * positions in pixels. A line taller than the page still gets a page of its own. No lines → one empty page.
     */
    fun pageStartLines(lineTops: IntArray, lineBottoms: IntArray, pageHeight: Int): IntArray {
        require(lineTops.size == lineBottoms.size)
        if (lineTops.isEmpty()) return intArrayOf(0)
        val starts = ArrayList<Int>()
        var line = 0
        while (line < lineTops.size) {
            starts += line
            val pageTop = lineTops[line]
            line++
            while (line < lineTops.size && lineBottoms[line] - pageTop <= pageHeight) line++
        }
        return starts.toIntArray()
    }

    /** Page containing character [offset], given each page's first character offset (ascending). */
    fun pageForOffset(pageStartOffsets: IntArray, offset: Int): Int {
        if (pageStartOffsets.isEmpty()) return 0
        var low = 0
        var high = pageStartOffsets.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (pageStartOffsets[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }

    /**
     * Splits long text into sections of at most [maxChars] characters, preferring paragraph breaks, so each
     * StaticLayout stays small. Returns the start offset of every section (first is always 0).
     */
    fun sectionStarts(text: CharSequence, maxChars: Int): IntArray {
        require(maxChars > 1)
        val starts = arrayListOf(0)
        var position = 0
        while (text.length - position > maxChars) {
            val limit = position + maxChars
            var cut = -1
            for (i in limit - 1 downTo position + maxChars / 2) {
                if (text[i] == '\n') {
                    cut = i + 1
                    break
                }
            }
            if (cut < 0) {
                cut = limit
                // Do not split a surrogate pair.
                if (Character.isLowSurrogate(text[cut])) cut--
            }
            starts += cut
            position = cut
        }
        return starts.toIntArray()
    }
}
