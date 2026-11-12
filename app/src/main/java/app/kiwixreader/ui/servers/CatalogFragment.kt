package app.kiwixreader.ui.servers

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.kiwixreader.R
import app.kiwixreader.container
import app.kiwixreader.data.ServerEntity
import app.kiwixreader.databinding.FragmentListBinding
import app.kiwixreader.net.sameOrigin
import app.kiwixreader.offline.ZimDownloads
import app.kiwixreader.remote.KiwixServe
import app.kiwixreader.remote.OpdsBook
import app.kiwixreader.ui.common.Format
import app.kiwixreader.ui.common.Row
import app.kiwixreader.ui.common.RowAdapter
import app.kiwixreader.ui.mainActivity
import app.kiwixreader.ui.reader.ArticleReaderFragment
import app.kiwixreader.ui.setTitle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Books hosted by one kiwix-serve instance, read from its OPDS catalog page by page. */
class CatalogFragment : Fragment(R.layout.fragment_list) {
    private var binding: FragmentListBinding? = null
    private lateinit var adapter: RowAdapter
    private var server: ServerEntity? = null
    private val books = ArrayList<OpdsBook>()
    private var total: Int? = null
    private var query: String? = null
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var endReached = false

    private val serverId get() = requireArguments().getLong(ARG_SERVER)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentListBinding.bind(view).also { binding = it }
        adapter = RowAdapter(::open, ::offerDownload)
        val layoutManager = LinearLayoutManager(requireContext())
        binding.list.layoutManager = layoutManager
        binding.list.adapter = adapter
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (layoutManager.findLastVisibleItemPosition() >= books.size - 10) loadMore()
            }
        })
        binding.emptyButton.setText(R.string.retry)
        binding.emptyButton.setOnClickListener { reload() }
        requireActivity().addMenuProvider(menu, viewLifecycleOwner, Lifecycle.State.RESUMED)

        viewLifecycleOwner.lifecycleScope.launch {
            server = container.servers.get(serverId)
            val current = server ?: run {
                showError(getString(R.string.server_missing))
                return@launch
            }
            setTitle(current.displayName, current.baseUrl)
            if (books.isEmpty()) reload() else render()
        }
    }

    private fun reload() {
        books.clear()
        total = null
        endReached = false
        loadJob?.cancel()
        loadJob = null
        render()
        loadMore()
    }

    private fun loadMore() {
        val current = server ?: return
        if (loadJob?.isActive == true || endReached) return
        val binding = binding ?: return
        binding.loading.isVisible = true
        binding.empty.isVisible = false
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val feed = container.servers.catalog(current, start = books.size, count = PAGE, query = query)
                books += feed.books
                total = feed.totalResults
                endReached = feed.books.size < PAGE || (total != null && books.size >= total!!)
                render()
            } catch (e: Exception) {
                if (books.isEmpty()) showError(getString(R.string.catalog_error, e.message.orEmpty()))
            } finally {
                this@CatalogFragment.binding?.loading?.isVisible = false
            }
        }
    }

    private fun render() {
        val binding = binding ?: return
        val context = requireContext()
        adapter.submitList(
            books.mapIndexed { index, book ->
                Row(
                    key = "book:$index:${book.id}",
                    title = book.title,
                    subtitle = book.summary.ifBlank { null },
                    detail = Format.joined(
                        Format.language(book.language),
                        book.flavour.ifBlank { null },
                        resources.getQuantityString(R.plurals.article_count, book.articleCount, Format.count(book.articleCount)),
                        if (book.sizeBytes > 0) Format.size(context, book.sizeBytes) else null,
                        if (book.downloadUrl != null) getString(R.string.downloadable) else null,
                    ),
                    icon = R.drawable.ic_library,
                    payload = book,
                )
            },
        )
        binding.empty.isVisible = books.isEmpty() && endReached
        if (books.isEmpty() && endReached) {
            binding.emptyText.setText(if (query.isNullOrBlank()) R.string.catalog_empty else R.string.no_results)
            binding.emptyButton.isVisible = false
        }
    }

    private fun showError(message: String) {
        val binding = binding ?: return
        binding.empty.isVisible = true
        binding.emptyText.text = message
        binding.emptyButton.isVisible = server != null
    }

    private fun open(row: Row) {
        val book = row.payload as? OpdsBook ?: return
        val content = book.contentUrl
        if (content == null) {
            snackbar(getString(R.string.book_not_browsable))
            return
        }
        mainActivity.push(ArticleReaderFragment.forRemote(content.toString(), book.title, server?.baseUrl))
    }

    private fun offerDownload(row: Row) {
        val book = row.payload as? OpdsBook ?: return
        val link = book.downloadUrl
        if (link == null) {
            snackbar(getString(R.string.download_not_offered))
            return
        }
        val url = KiwixServe.directZimUrl(link)
        val base = server?.baseUrl?.toHttpUrlOrNull()
        val elsewhere = base == null || !sameOrigin(base, url)
        val size = if (book.sizeBytes > 0) Format.size(requireContext(), book.sizeBytes) else getString(R.string.unknown_size)
        val message = buildString {
            append(getString(R.string.download_question, book.title, size))
            // The only traffic should go to configured servers, so a third-party host needs explicit consent.
            if (elsewhere) append("\n\n").append(getString(R.string.download_other_host, url.host))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.download_zim)
            .setMessage(message)
            .setPositiveButton(R.string.download) { _, _ ->
                val fileName = url.pathSegments.lastOrNull { it.isNotEmpty() }?.takeIf { it.endsWith(".zim") }
                    ?: "${book.name.ifBlank { book.id }}.zim"
                ZimDownloads.enqueue(requireContext(), url.toString(), book.title, fileName)
                snackbar(getString(R.string.download_started))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun snackbar(message: String) {
        binding?.root?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
    }

    private val menu = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.catalog, menu)
            val searchItem = menu.findItem(R.id.action_search)
            val searchView = searchItem.actionView as SearchView
            searchView.queryHint = getString(R.string.search_catalog)
            if (!query.isNullOrEmpty()) {
                searchItem.expandActionView()
                searchView.setQuery(query, false)
            }
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(text: String?): Boolean {
                    applyQuery(text, debounce = false)
                    searchView.clearFocus()
                    return true
                }

                override fun onQueryTextChange(text: String?): Boolean {
                    applyQuery(text, debounce = true)
                    return true
                }
            })
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            if (menuItem.itemId == R.id.action_refresh) {
                reload()
                return true
            }
            return false
        }
    }

    private fun applyQuery(text: String?, debounce: Boolean) {
        // The SearchView reports an empty query while the menu is torn down on navigation; keep the filter.
        if (view == null || !isResumed) return
        val normalized = text?.trim()?.ifEmpty { null }
        if (normalized == query) return
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            if (debounce) delay(400)
            query = normalized
            reload()
        }
    }

    override fun onDestroyView() {
        binding = null
        loadJob = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_SERVER = "server"
        private const val PAGE = 50

        fun newInstance(serverId: Long) = CatalogFragment().apply { arguments = bundleOf(ARG_SERVER to serverId) }
    }
}
