package app.burrow.books

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginatorTest {
    /** [count] lines of [height] px each, stacked from y = 0. */
    private fun lines(count: Int, height: Int) =
        IntArray(count) { it * height } to IntArray(count) { (it + 1) * height }

    @Test
    fun `fills each page with as many whole lines as fit`() {
        val (tops, bottoms) = lines(10, 20)
        // 65 px page -> 3 lines (60 px) per page.
        assertArrayEquals(intArrayOf(0, 3, 6, 9), Paginator.pageStartLines(tops, bottoms, 65))
    }

    @Test
    fun `a line ending exactly at the page bottom stays on the page`() {
        val (tops, bottoms) = lines(6, 20)
        assertArrayEquals(intArrayOf(0, 3), Paginator.pageStartLines(tops, bottoms, 60))
    }

    @Test
    fun `a line taller than the page gets its own page`() {
        val tops = intArrayOf(0, 20, 220)
        val bottoms = intArrayOf(20, 220, 240)
        assertArrayEquals(intArrayOf(0, 1, 2), Paginator.pageStartLines(tops, bottoms, 100))
    }

    @Test
    fun `lines of different heights are measured from the page top`() {
        // A heading (40 px) followed by body lines (20 px) on a 100 px page.
        val tops = intArrayOf(0, 40, 60, 80, 100, 120)
        val bottoms = intArrayOf(40, 60, 80, 100, 120, 140)
        assertArrayEquals(intArrayOf(0, 4), Paginator.pageStartLines(tops, bottoms, 100))
    }

    @Test
    fun `no lines still yields one empty page`() {
        assertArrayEquals(intArrayOf(0), Paginator.pageStartLines(IntArray(0), IntArray(0), 100))
    }

    @Test
    fun `finds the page that contains a character offset`() {
        val pageStarts = intArrayOf(0, 120, 260, 400)
        assertEquals(0, Paginator.pageForOffset(pageStarts, 0))
        assertEquals(0, Paginator.pageForOffset(pageStarts, 119))
        assertEquals(1, Paginator.pageForOffset(pageStarts, 120))
        assertEquals(2, Paginator.pageForOffset(pageStarts, 399))
        assertEquals(3, Paginator.pageForOffset(pageStarts, 10_000))
        assertEquals(0, Paginator.pageForOffset(IntArray(0), 5))
    }

    @Test
    fun `reading position survives a font change`() {
        // Same text paginated at two sizes: the page holding the remembered offset is found again.
        val small = intArrayOf(0, 500, 1000, 1500, 2000)
        val large = intArrayOf(0, 300, 600, 900, 1200, 1500, 1800, 2100)
        val position = small[2] // reading page 3 at the small size
        val page = Paginator.pageForOffset(large, position)
        assertTrue(large[page] <= position && (page == large.lastIndex || large[page + 1] > position))
        assertEquals(3, page)
    }

    @Test
    fun `long text is split into sections at paragraph breaks`() {
        val paragraph = "a".repeat(29) + "\n" // 30 chars
        val text = paragraph.repeat(10) // 300 chars
        val starts = Paginator.sectionStarts(text, 100)
        assertArrayEquals(intArrayOf(0, 90, 180, 270), starts)
        starts.drop(1).forEach { assertEquals('\n', text[it - 1]) }
    }

    @Test
    fun `text without breaks is split hard at the limit`() {
        assertArrayEquals(intArrayOf(0, 100, 200), Paginator.sectionStarts("x".repeat(250), 100))
        assertArrayEquals(intArrayOf(0), Paginator.sectionStarts("short", 100))
    }

    @Test
    fun `a surrogate pair is never split`() {
        val text = "x".repeat(99) + "📖" + "x".repeat(50) // emoji at index 99..100
        val starts = Paginator.sectionStarts(text, 100)
        assertEquals(99, starts[1])
    }
}
