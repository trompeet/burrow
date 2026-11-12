package app.burrow.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser

class OpdsParserTest {
    // Shape of libkiwix's static/templates/catalog_v2_entries.xml + catalog_v2_entry.xml.
    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom"
              xmlns:dc="http://purl.org/dc/terms/"
              xmlns:opds="https://specs.opds.io/opds-1.2"
              xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
          <id>ae2628a2-4c48-93f4-6b27-b706ce92d96e</id>
          <link rel="self" href="/kiwix/catalog/v2/entries?start=0&amp;count=50" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
          <title>Filtered Entries (start=0&amp;count=50)</title>
          <updated>2026-11-12T17:41:23Z</updated>
          <totalResults>2</totalResults>
          <startIndex>0</startIndex>
          <itemsPerPage>50</itemsPerPage>
          <entry>
            <id>urn:uuid:f215c060-6efa-ed76-ebca-82706f69f20e</id>
            <title>Ray Charles</title>
            <updated>2026-10-31T00:00:00Z</updated>
            <summary>Wikipedia articles about Ray Charles</summary>
            <language>eng</language>
            <name>wikipedia_en_ray-charles</name>
            <flavour>maxi</flavour>
            <category>wikipedia</category>
            <tags>wikipedia;_category:wikipedia;_pictures:yes</tags>
            <articleCount>25572</articleCount>
            <mediaCount>627</mediaCount>
            <link rel="http://opds-spec.org/image/thumbnail"
                  href="/kiwix/catalog/v2/illustration/f215c060-6efa-ed76-ebca-82706f69f20e/?size=48"
                  type="image/png;width=48;height=48;scale=1"/>
            <link type="text/html" href="https://browse.example.org/content/wikipedia_en_ray-charles_maxi_2026-10" />
            <author>
              <name>Wikipedia</name>
            </author>
            <publisher>
              <name>openZIM</name>
            </publisher>
            <dc:issued>2026-10-31T00:00:00Z</dc:issued>
            <link rel="http://opds-spec.org/acquisition/open-access" type="application/x-zim" href="https://download.example.org/zim/wikipedia_en_ray-charles_maxi_2026-10.zim.meta4" length="29887488" />
          </entry>
          <entry>
            <id>urn:uuid:9f1a2b3c-0000-0000-0000-000000000001</id>
            <title>Test ZIM &amp; friends</title>
            <updated>2024-01-01T00:00:00Z</updated>
            <summary></summary>
            <language>fra,eng</language>
            <name>testzim</name>
            <flavour></flavour>
            <category></category>
            <tags></tags>
            <articleCount>12</articleCount>
            <mediaCount>3</mediaCount>
            <link type="text/html" href="/kiwix/content/testzim" />
            <author>
              <name>Kiwix</name>
            </author>
            <publisher>
              <name></name>
            </publisher>
            <dc:issued>2024-01-01T00:00:00Z</dc:issued>
          </entry>
        </feed>
    """.trimIndent()

    private val feedUrl = "http://192.168.1.10:8080/kiwix/catalog/v2/entries?start=0&count=50".toHttpUrl()

    private fun parse(xml: String) = OpdsParser.parse(xml.byteInputStream(), feedUrl, KXmlParser())

    @Test
    fun `parses feed totals and every entry`() {
        val result = parse(feed)
        assertEquals(2, result.totalResults)
        assertEquals(listOf("Ray Charles", "Test ZIM & friends"), result.books.map { it.title })
    }

    @Test
    fun `reads entry fields and the author name, not the publisher`() {
        val book = parse(feed).books[0]
        assertEquals("f215c060-6efa-ed76-ebca-82706f69f20e", book.id)
        assertEquals("wikipedia_en_ray-charles", book.name)
        assertEquals("eng", book.language)
        assertEquals(25572, book.articleCount)
        assertEquals(627, book.mediaCount)
        assertEquals("Wikipedia", book.author)
        assertEquals(29887488L, book.sizeBytes)
    }

    @Test
    fun `resolves absolute and relative links against the feed URL`() {
        val (remote, local) = parse(feed).books
        assertEquals("https://browse.example.org/content/wikipedia_en_ray-charles_maxi_2026-10", remote.contentUrl.toString())
        assertEquals("https://download.example.org/zim/wikipedia_en_ray-charles_maxi_2026-10.zim.meta4", remote.downloadUrl.toString())
        assertEquals(
            "http://192.168.1.10:8080/kiwix/catalog/v2/illustration/f215c060-6efa-ed76-ebca-82706f69f20e/?size=48",
            remote.thumbnailUrl.toString(),
        )
        assertEquals("http://192.168.1.10:8080/kiwix/content/testzim", local.contentUrl.toString())
        assertNull(local.downloadUrl)
        assertNull(local.thumbnailUrl)
    }

    @Test
    fun `empty feed has no books`() {
        val empty = """<?xml version="1.0" encoding="UTF-8"?><feed xmlns="http://www.w3.org/2005/Atom"><id>x</id><title>All Entries</title></feed>"""
        val result = parse(empty)
        assertEquals(0, result.books.size)
        assertNull(result.totalResults)
    }

    // ---- kiwix-serve URLs ---------------------------------------------------------------------------------

    @Test
    fun `book name and search URL come from the content link, including a URL root`() {
        val content = "http://192.168.1.10:8080/kiwix/content/testzim".toHttpUrl()
        assertEquals("testzim", KiwixServe.bookName(content))
        assertEquals(
            "http://192.168.1.10:8080/kiwix/search?books.name=testzim&pattern=ray%20charles&format=xml&pageLength=50",
            KiwixServe.searchUrl(content, "ray charles").toString(),
        )
        assertNull(KiwixServe.searchUrl("http://host/not-a-book".toHttpUrl(), "x"))
    }

    @Test
    fun `entries URL keeps the server root`() {
        assertEquals(
            "http://host:8080/kiwix/catalog/v2/entries?start=50&count=50&q=science",
            KiwixServe.entriesUrl("http://host:8080/kiwix".toHttpUrl(), 50, 50, " science ").toString(),
        )
        assertEquals(
            "http://host:8080/catalog/v2/entries?start=0&count=1",
            KiwixServe.entriesUrl("http://host:8080/".toHttpUrl(), 0, 1, null).toString(),
        )
    }

    @Test
    fun `server addresses are normalised`() {
        assertEquals("http://192.168.1.10:8080", KiwixServe.normalizeBaseUrl("192.168.1.10:8080")?.let(KiwixServe::baseUrlString))
        assertEquals("https://example.org/kiwix", KiwixServe.normalizeBaseUrl(" https://example.org/kiwix/ ")?.let(KiwixServe::baseUrlString))
        assertEquals("http://host:8080", KiwixServe.normalizeBaseUrl("http://user:pw@host:8080/?x=1#y")?.let(KiwixServe::baseUrlString))
        assertNull(KiwixServe.normalizeBaseUrl(""))
        assertNull(KiwixServe.normalizeBaseUrl("ftp://host"))
    }

    @Test
    fun `metalink downloads point at the ZIM itself`() {
        assertEquals(
            "https://download.example.org/zim/a.zim",
            KiwixServe.directZimUrl("https://download.example.org/zim/a.zim.meta4".toHttpUrl()).toString(),
        )
    }

    // ---- Search results -----------------------------------------------------------------------------------

    @Test
    fun `parses full-text search results with highlighted snippets`() {
        // libkiwix static/templates/search_result.xml; the snippet is inserted unescaped.
        val rss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/" xmlns:atom="http://www.w3.org/2005/Atom">
              <channel>
                <title>Search: ray</title>
                <link>/kiwix/search?books.name=testzim&amp;pattern=ray&amp;format=xml&amp;start=0&amp;pageLength=25</link>
                <description>Search result for ray</description>
                <opensearch:totalResults>2</opensearch:totalResults>
                <opensearch:startIndex>0</opensearch:startIndex>
                <opensearch:itemsPerPage>25</opensearch:itemsPerPage>
                <item>
                  <title>Ray Charles</title>
                  <link>/kiwix/content/testzim/A/Ray_Charles</link>
                  <description>American singer <b>Ray</b> Charles was...</description>
                  <book><title>Test ZIM</title></book>
                  <wordCount>4200</wordCount>
                </item>
                <item>
                  <title>Ray (film)</title>
                  <link>/kiwix/content/testzim/A/Ray_(film)</link>
                </item>
              </channel>
            </rss>
        """.trimIndent()
        val url = "http://192.168.1.10:8080/kiwix/search?books.name=testzim&pattern=ray&format=xml".toHttpUrl()
        val results = SearchResultsParser.parse(rss.byteInputStream(), url, KXmlParser())
        assertEquals(2, results.totalResults)
        assertEquals(listOf("Ray Charles", "Ray (film)"), results.hits.map { it.title })
        assertEquals("http://192.168.1.10:8080/kiwix/content/testzim/A/Ray_Charles", results.hits[0].url.toString())
        assertEquals("American singer Ray Charles was...", results.hits[0].snippet)
        assertEquals("", results.hits[1].snippet)
    }
}
