package app.burrow.ui.reader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import app.burrow.R
import app.burrow.container
import app.burrow.data.ArticleBookmarkEntity
import app.burrow.data.OfflineArticleEntity
import app.burrow.databinding.DialogListBinding
import app.burrow.databinding.FragmentArticleReaderBinding
import app.burrow.ui.common.Row
import app.burrow.ui.common.RowAdapter
import app.burrow.ui.setTitle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/** Article reader for local ZIM files and remote kiwix-serve books. */
class ArticleReaderFragment : Fragment(R.layout.fragment_article_reader) {
    private var binding: FragmentArticleReaderBinding? = null
    private var bookmarks: List<ArticleBookmarkEntity> = emptyList()
    private var searchItem: MenuItem? = null
    private var source: ArticleSource? = null

    private val viewModel: ArticleReaderViewModel by viewModels {
        val app = container
        val application = requireActivity().application
        val args = requireArguments()
        viewModelFactory { initializer { ArticleReaderViewModel(app, application, args) } }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val binding = binding ?: return
            when {
                searchItem?.isActionViewExpanded == true -> searchItem?.collapseActionView()
                binding.webView.canGoBack() -> binding.webView.goBack()
            }
            updateNavigation()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentArticleReaderBinding.bind(view).also { binding = it }
        setTitle(requireArguments().getString(ARG_TITLE))
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        requireActivity().addMenuProvider(menu, viewLifecycleOwner, Lifecycle.State.RESUMED)

        val hitsAdapter = RowAdapter(onClick = { row ->
            searchItem?.collapseActionView()
            binding.webView.loadUrl(row.key)
        })
        binding.searchResults.layoutManager = LinearLayoutManager(requireContext())
        binding.searchResults.adapter = hitsAdapter

        binding.back.setOnClickListener { if (binding.webView.canGoBack()) binding.webView.goBack() }
        binding.forward.setOnClickListener { if (binding.webView.canGoForward()) binding.webView.goForward() }
        binding.home.setOnClickListener { source?.let { binding.webView.loadUrl(it.homeUrl) } }
        binding.bookmark.setOnClickListener {
            val url = binding.webView.url ?: return@setOnClickListener
            viewModel.toggleBookmark(url, binding.webView.title.orEmpty(), isBookmarked(url))
        }
        binding.bookmarks.setOnClickListener { showBookmarks() }

        val restoredUrl = savedInstanceState?.getString(STATE_URL)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        when (state) {
                            ArticleReaderViewModel.State.Loading -> binding.pageProgress.isVisible = true
                            is ArticleReaderViewModel.State.Error -> {
                                binding.pageProgress.isVisible = false
                                binding.message.isVisible = true
                                binding.message.text = getString(R.string.cannot_open, state.message)
                            }
                            is ArticleReaderViewModel.State.Ready -> if (source == null) {
                                source = state.source
                                setTitle(state.source.bookTitle.ifBlank { requireArguments().getString(ARG_TITLE) })
                                setUpWebView(binding.webView, state.source)
                                binding.webView.loadUrl(restoredUrl ?: viewModel.startUrl ?: state.source.homeUrl)
                                requireActivity().invalidateMenu()
                            }
                        }
                    }
                }
                launch {
                    viewModel.bookmarks.collect {
                        bookmarks = it
                        updateNavigation()
                    }
                }
                launch {
                    viewModel.hits.collect { hits ->
                        binding.searchResults.isVisible = hits != null
                        binding.noResults.isVisible = hits?.isEmpty() == true
                        hitsAdapter.submitList(
                            hits.orEmpty().map { Row(key = it.url, title = it.title, subtitle = it.snippet.ifBlank { null }, icon = R.drawable.ic_article) },
                        )
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView(webView: WebView, source: ArticleSource) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            safeBrowsingEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE // caching is done by OkHttp
            mediaPlaybackRequiresUserGesture = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                isAlgorithmicDarkeningAllowed = night
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                source.intercept(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (source.handles(url)) return false
                openExternally(request.url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                binding?.pageProgress?.isVisible = true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                binding?.pageProgress?.isVisible = false
                setTitle(source.bookTitle, view.title?.takeIf { it.isNotBlank() && it != url })
                updateNavigation()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = updateNavigation()
        }
    }

    private fun isBookmarked(url: String) = bookmarks.any { it.url == url }

    private fun updateNavigation() {
        val binding = binding ?: return
        val webView = binding.webView
        binding.back.isEnabled = webView.canGoBack()
        binding.forward.isEnabled = webView.canGoForward()
        val bookmarked = webView.url?.let(::isBookmarked) == true
        binding.bookmark.setImageResource(if (bookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
        binding.bookmark.contentDescription = getString(if (bookmarked) R.string.remove_bookmark else R.string.add_bookmark)
        backCallback.isEnabled = webView.canGoBack() || searchItem?.isActionViewExpanded == true
    }

    private fun openExternally(uri: Uri) {
        if (uri.scheme !in setOf("http", "https", "mailto")) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.external_link)
            .setMessage(getString(R.string.external_link_question, uri.toString()))
            .setPositiveButton(R.string.open) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                    snackbar(getString(R.string.no_app_for_link))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBookmarks() {
        val list = DialogListBinding.inflate(LayoutInflater.from(requireContext()))
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bookmarks)
            .setView(list.root)
            .setNegativeButton(R.string.close, null)
            .create()
        val adapter = RowAdapter(
            onClick = { row ->
                dialog.dismiss()
                binding?.webView?.loadUrl((row.payload as ArticleBookmarkEntity).url)
            },
            onLongClick = { row -> viewModel.deleteBookmark(row.payload as ArticleBookmarkEntity) },
        )
        list.list.layoutManager = LinearLayoutManager(requireContext())
        list.list.adapter = adapter
        list.empty.setText(R.string.bookmarks_empty)
        val job = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.bookmarks.collect { items ->
                list.empty.isVisible = items.isEmpty()
                adapter.submitList(
                    items.map { Row(key = it.url, title = it.title.ifBlank { it.url }, icon = R.drawable.ic_bookmark, payload = it) },
                )
            }
        }
        dialog.setOnDismissListener { job.cancel() }
        dialog.show()
    }

    private fun saveForOffline() {
        val webView = binding?.webView ?: return
        val url = webView.url ?: return
        snackbar(getString(R.string.saving_offline))
        viewModel.saveOffline(url, webView.title.orEmpty()) { error ->
            if (isAdded) snackbar(if (error == null) getString(R.string.saved_offline) else getString(R.string.save_failed, error))
        }
    }

    private fun snackbar(message: String) {
        binding?.root?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
    }

    private val menu = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.article_reader, menu)
            menu.findItem(R.id.action_save_offline).isVisible = source is ArticleSource.Remote
            val item = menu.findItem(R.id.action_search)
            item.isVisible = source != null && (source as? ArticleSource.Remote)?.searchSupported != false
            searchItem = item
            val searchView = item.actionView as SearchView
            searchView.queryHint = getString(R.string.search_articles)
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    viewModel.search(query.orEmpty(), debounce = false)
                    searchView.clearFocus()
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.search(newText.orEmpty(), debounce = true)
                    return true
                }
            })
            item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                    backCallback.isEnabled = true
                    return true
                }

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    viewModel.clearSearch()
                    binding?.root?.post { updateNavigation() }
                    return true
                }
            })
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            if (menuItem.itemId == R.id.action_save_offline) {
                saveForOffline()
                return true
            }
            return false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding?.webView?.url?.let { outState.putString(STATE_URL, it) }
    }

    override fun onDestroyView() {
        binding?.webView?.apply {
            stopLoading()
            webViewClient = WebViewClient()
            destroy()
        }
        binding = null
        searchItem = null
        source = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_ZIM_URI = "zim_uri"
        const val ARG_CONTENT_ROOT = "content_root"
        const val ARG_SERVER_BASE = "server_base"
        const val ARG_TITLE = "title"
        const val ARG_START_URL = "start_url"
        private const val STATE_URL = "url"

        fun forZim(uri: String, title: String) = ArticleReaderFragment().apply {
            arguments = bundleOf(ARG_ZIM_URI to uri, ARG_TITLE to title)
        }

        fun forRemote(contentRoot: String, title: String, serverBase: String?) = ArticleReaderFragment().apply {
            arguments = bundleOf(ARG_CONTENT_ROOT to contentRoot, ARG_TITLE to title, ARG_SERVER_BASE to serverBase)
        }

        fun forOffline(article: OfflineArticleEntity) = ArticleReaderFragment().apply {
            arguments = bundleOf(
                ARG_CONTENT_ROOT to article.contentRoot,
                ARG_TITLE to article.bookTitle,
                ARG_SERVER_BASE to article.serverBaseUrl,
                ARG_START_URL to article.url,
            )
        }
    }
}
