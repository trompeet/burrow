package app.burrow.books

import org.junit.Assert.assertEquals
import org.junit.Test

class GutenbergTest {
    private val header = """
        The Project Gutenberg eBook of Pride and Prejudice

        This ebook is for the use of anyone anywhere in the United States and
        most other parts of the world at no cost and with almost no restrictions
        whatsoever.

        Title: Pride and Prejudice

        Author: Jane Austen

        *** START OF THE PROJECT GUTENBERG EBOOK PRIDE AND PREJUDICE ***
    """.trimIndent()

    private val footer = """
        *** END OF THE PROJECT GUTENBERG EBOOK PRIDE AND PREJUDICE ***

        Updated editions will replace the previous one—the old editions will
        be renamed.
    """.trimIndent()

    private val body = "Chapter 1\n\nIt is a truth universally acknowledged, that a single man in\npossession of a good fortune, must be in want of a wife."

    @Test
    fun `removes header and footer around the START and END markers`() {
        assertEquals(body, Gutenberg.strip("$header\n\n\n$body\n\n\n\n$footer\n"))
    }

    @Test
    fun `handles Windows line endings`() {
        val text = "$header\n\n$body\n\n$footer".replace("\n", "\r\n")
        assertEquals(body, Gutenberg.strip(text))
    }

    @Test
    fun `accepts older marker variants`() {
        val text = "Licence...\n***START OF THIS PROJECT GUTENBERG EBOOK EMMA***\n\n$body\n\n*** end of this project gutenberg ebook emma ***\nLicence"
        assertEquals(body, Gutenberg.strip(text))
    }

    @Test
    fun `skips a START marker that wraps onto the next line`() {
        val text = "Licence\n*** START OF THIS PROJECT GUTENBERG EBOOK THE ADVENTURES\nOF SHERLOCK HOLMES ***\n\n$body\n*** END OF THIS PROJECT GUTENBERG EBOOK ***"
        assertEquals(body, Gutenberg.strip(text))
    }

    @Test
    fun `drops the End of the Project Gutenberg line above the END marker`() {
        val text = "$header\n$body\n\n\nEnd of the Project Gutenberg EBook of Pride and Prejudice, by Jane Austen\n\n$footer"
        assertEquals(body, Gutenberg.strip(text))
    }

    @Test
    fun `keeps the rest of the book when the END marker is missing`() {
        assertEquals(body, Gutenberg.strip("$header\n$body\n"))
    }

    @Test
    fun `leaves text without markers untouched`() {
        val text = "Just a text file.\n\nWith *** stars *** inside.\n"
        assertEquals(text, Gutenberg.strip(text))
    }

    @Test
    fun `an END marker before START is not used`() {
        val text = "*** END OF nothing ***\nLicence\n*** START OF THE PROJECT GUTENBERG EBOOK X ***\n$body"
        assertEquals(body, Gutenberg.strip(text))
    }
}
