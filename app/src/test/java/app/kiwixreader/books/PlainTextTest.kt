package app.kiwixreader.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainTextTest {
    @Test
    fun `joins hard-wrapped lines into paragraphs`() {
        val text = "It is a truth universally acknowledged, that a single man in\npossession of a good fortune, must be in want of a wife.\n\nSecond paragraph\nwraps here as well, with a long enough line to count as prose."
        val paragraphs = PlainText.paragraphs(text)
        assertEquals(2, paragraphs.size)
        assertEquals(
            "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.",
            paragraphs[0].text,
        )
    }

    @Test
    fun `keeps line breaks in verse`() {
        val verse = "Tyger Tyger, burning bright,\nIn the forests of the night;\nWhat immortal hand or eye,"
        assertEquals(verse, PlainText.paragraphs(verse).single().text)
    }

    @Test
    fun `detects chapter headings`() {
        assertTrue(PlainText.isHeading("CHAPTER I."))
        assertTrue(PlainText.isHeading("Chapter 12"))
        assertTrue(PlainText.isHeading("BOOK ONE: THE RIVER"))
        assertTrue(PlainText.isHeading("STAVE III. THE SECOND OF THE THREE SPIRITS."))
        assertFalse(PlainText.isHeading("Chapters are long."))
        assertFalse(PlainText.isHeading("Chapter one ended long before the night had quite worn itself into a grey and cheerless morning."))
        val paragraphs = PlainText.paragraphs("CHAPTER I.\n\nText.")
        assertTrue(paragraphs[0].isHeading)
        assertFalse(paragraphs[1].isHeading)
    }

    @Test
    fun `heading with a title line is one heading paragraph`() {
        val paragraphs = PlainText.paragraphs("CHAPTER I.\nDown the Rabbit-Hole\n\nAlice was beginning to get very tired of sitting by her sister on the bank.")
        assertTrue(paragraphs[0].isHeading)
        assertEquals("CHAPTER I.\nDown the Rabbit-Hole", paragraphs[0].text)
        assertFalse(paragraphs[1].isHeading)
    }

    @Test
    fun `indented contents lists keep one entry per line`() {
        val contents = " CHAPTER I.     Down the Rabbit-Hole\n CHAPTER II.    The Pool of Tears\n CHAPTER III.   A Caucus-Race and a Long Tale"
        val paragraph = PlainText.paragraphs(contents).single()
        assertEquals("CHAPTER I. Down the Rabbit-Hole\nCHAPTER II. The Pool of Tears\nCHAPTER III. A Caucus-Race and a Long Tale", paragraph.text)
    }

    @Test
    fun `decodes UTF-8 with BOM and falls back to Windows-1252`() {
        assertEquals("café", PlainText.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "café".toByteArray()))
        assertEquals("café", PlainText.decode(byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte())))
    }
}
