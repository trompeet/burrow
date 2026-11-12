package app.kiwixreader.ui.books

import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.content.ContextCompat
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
import app.kiwixreader.R
import app.kiwixreader.books.BookContent
import app.kiwixreader.books.Paginator
import app.kiwixreader.container
import app.kiwixreader.data.BookBookmarkEntity
import app.kiwixreader.data.ReaderFont
import app.kiwixreader.data.ReaderPrefs
import app.kiwixreader.data.ReaderTheme
import app.kiwixreader.databinding.DialogListBinding
import app.kiwixreader.databinding.FragmentBookReaderBinding
import app.kiwixreader.databinding.SheetReaderSettingsBinding
import app.kiwixreader.ui.common.Row
import app.kiwixreader.ui.mainActivity
import app.kiwixreader.ui.setTitle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Paginated e-reader for EPUB, TXT and HTML books. */
class BookReaderFragment : Fragment(R.layout.fragment_book_reader), PageView.Listener {
    private var binding: FragmentBookReaderBinding? = null
    private var content: BookContent? = null
    private var prefs: ReaderPrefs? = null
    private var bookmarks: List<BookBookmarkEntity> = emptyList()

    /** The laid-out current section. */
    private class Paged(
        val section: Int,
        val layout: StaticLayout,
        val pageStartLines: IntArray,
        val pageStartOffsets: IntArray,
    ) {
        val pageCount get() = pageStartLines.size
        fun lastLine(page: Int) = if (page + 1 < pageStartLines.size) pageStartLines[page + 1] - 1 else layout.lineCount - 1
    }

    private var paged: Paged? = null
    private var page = 0
    private var layoutJob: Job? = null

    private val viewModel: BookReaderViewModel by viewModels {
        val app = container
        val id = requireArguments().getLong(ARG_BOOK)
        viewModelFactory { initializer { BookReaderViewModel(app, id) } }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentBookReaderBinding.bind(view).also { binding = it }
        binding.page.listener = this
        setTitle(null)
        requireActivity().addMenuProvider(menu, viewLifecycleOwner, Lifecycle.State.RESUMED)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        binding.loading.isVisible = state is BookReaderViewModel.State.Loading
                        binding.message.isVisible = state is BookReaderViewModel.State.Error
                        when (state) {
                            is BookReaderViewModel.State.Error -> binding.message.text = getString(R.string.cannot_open, state.message)
                            is BookReaderViewModel.State.Ready -> if (content == null) {
                                content = state.content
                                setTitle(state.book.title, state.book.author.ifBlank { null })
                                viewModel.touch()
                                relayout(viewModel.section, viewModel.offset)
                            }
                            else -> Unit
                        }
                    }
                }
                launch {
                    viewModel.prefs.collect {
                        val changed = it != prefs
                        prefs = it
                        applyColors()
                        if (changed) relayout(viewModel.section, viewModel.offset)
                    }
                }
                launch { viewModel.bookmarks.collect { bookmarks = it } }
            }
        }
    }

    // ---- Layout -------------------------------------------------------------------------------------------

    private fun theme(prefs: ReaderPrefs): ReaderTheme = prefs.theme ?: run {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (night) ReaderTheme.DARK else ReaderTheme.LIGHT
    }

    private fun colors(theme: ReaderTheme): Pair<Int, Int> {
        val context = requireContext()
        val (bg, fg) = when (theme) {
            ReaderTheme.LIGHT -> R.color.reader_light_bg to R.color.reader_light_fg
            ReaderTheme.SEPIA -> R.color.reader_sepia_bg to R.color.reader_sepia_fg
            ReaderTheme.DARK -> R.color.reader_dark_bg to R.color.reader_dark_fg
        }
        return ContextCompat.getColor(context, bg) to ContextCompat.getColor(context, fg)
    }

    private fun applyColors() {
        val prefs = prefs ?: return
        val binding = binding ?: return
        val (bg, fg) = colors(theme(prefs))
        binding.root.setBackgroundColor(bg)
        binding.page.setColors(bg, (fg and 0x00ffffff) or (0x99 shl 24))
        binding.message.setTextColor(fg)
    }

    /** Lays out [section] and shows the page containing [offset] ([offset] < 0: the last page). */
    private fun relayout(section: Int, offset: Int) {
        val binding = binding ?: return
        val content = content ?: return
        val prefs = prefs ?: return
        val pageView = binding.page
        if (pageView.width == 0 || pageView.height == 0) return // onSizeChanged will call back
        val metrics = resources.displayMetrics
        val margin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, prefs.marginDp.toFloat(), metrics).toInt()
        val top = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, metrics).toInt()
        pageView.setPadding(margin, top, margin, 0)
        val width = pageView.contentWidth
        val height = pageView.contentHeight
        val (_, fg) = colors(theme(prefs))
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = fg
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, prefs.fontSizeSp, metrics)
            typeface = if (prefs.font == ReaderFont.SERIF) Typeface.SERIF else Typeface.SANS_SERIF
        }
        val text = content.sections[section]
        layoutJob?.cancel()
        layoutJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                    .setLineSpacing(0f, prefs.lineSpacing)
                    .setIncludePad(false)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
                    .build()
                val tops = IntArray(layout.lineCount) { layout.getLineTop(it) }
                val bottoms = IntArray(layout.lineCount) { layout.getLineBottom(it) }
                val starts = Paginator.pageStartLines(tops, bottoms, height)
                Paged(section, layout, starts, IntArray(starts.size) { layout.getLineStart(starts[it]) })
            }
            paged = result
            page = if (offset < 0) result.pageCount - 1 else Paginator.pageForOffset(result.pageStartOffsets, offset)
            render()
        }
    }

    private fun render() {
        val binding = binding ?: return
        val paged = paged ?: return
        val content = content ?: return
        val offset = paged.pageStartOffsets[page]
        viewModel.setPosition(paged.section, offset)
        val percent = (content.progress(paged.section, offset) * 100).roundToInt()
        binding.page.show(
            layout = paged.layout,
            firstLine = paged.pageStartLines[page],
            lastLine = paged.lastLine(page),
            footerStart = content.chapterTitleAt(paged.section, offset).orEmpty(),
            footerEnd = getString(R.string.percent, percent),
        )
    }

    override fun onNextPage() {
        val paged = paged ?: return
        val content = content ?: return
        when {
            page + 1 < paged.pageCount -> {
                page++
                render()
            }
            paged.section + 1 < content.sections.size -> relayout(paged.section + 1, 0)
        }
    }

    override fun onPreviousPage() {
        val paged = paged ?: return
        when {
            page > 0 -> {
                page--
                render()
            }
            paged.section > 0 -> relayout(paged.section - 1, -1)
        }
    }

    override fun onCenterTap() = mainActivity.setChromeVisible(!mainActivity.isChromeVisible)

    override fun onSizeChanged() {
        binding?.page?.post { relayout(viewModel.section, viewModel.offset) }
    }

    // ---- Dialogs ------------------------------------------------------------------------------------------

    private fun showToc() {
        val content = content ?: return
        if (content.toc.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.toc_empty).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        showList(
            title = getString(R.string.contents),
            rows = content.toc.mapIndexed { i, entry -> Row(key = "toc:$i", title = entry.title, indent = entry.depth, payload = entry) },
            onClick = { row ->
                val entry = row.payload as app.kiwixreader.books.TocEntry
                relayout(entry.section, entry.offset)
            },
        )
    }

    private fun showBookmarks() {
        val rows = bookmarks.map {
            Row(key = "bm:${it.id}", title = it.label, detail = getString(R.string.percent, (it.progress * 100).roundToInt()), icon = R.drawable.ic_bookmark, payload = it)
        }
        showList(
            title = getString(R.string.bookmarks),
            rows = rows,
            empty = getString(R.string.bookmarks_empty_book),
            onClick = { row ->
                val bookmark = row.payload as BookBookmarkEntity
                relayout(bookmark.section, bookmark.sectionOffset)
            },
            onLongClick = { row ->
                val bookmark = row.payload as BookBookmarkEntity
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(getString(R.string.delete_bookmark_question, bookmark.label))
                    .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteBookmark(bookmark.id) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
            positive = getString(R.string.add_bookmark) to { addBookmark() },
        )
    }

    private fun addBookmark() {
        val paged = paged ?: return
        val start = paged.pageStartOffsets[page]
        val text = paged.layout.text
        val label = text.subSequence(start, (start + 80).coerceAtMost(text.length)).toString()
            .replace(Regex("\\s+"), " ").trim().ifEmpty { getString(R.string.bookmark) }
        viewModel.addBookmark(label)
    }

    private fun showList(
        title: String,
        rows: List<Row>,
        empty: String? = null,
        onClick: (Row) -> Unit,
        onLongClick: ((Row) -> Unit)? = null,
        positive: Pair<String, () -> Unit>? = null,
    ) {
        val list = DialogListBinding.inflate(LayoutInflater.from(requireContext()))
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(list.root)
            .setNegativeButton(R.string.close, null)
        positive?.let { (label, action) -> builder.setPositiveButton(label) { _, _ -> action() } }
        val dialog = builder.create()
        val adapter = app.kiwixreader.ui.common.RowAdapter(
            onClick = {
                dialog.dismiss()
                onClick(it)
            },
            onLongClick = onLongClick?.let { handler -> { row: Row -> dialog.dismiss(); handler(row) } },
        )
        list.list.layoutManager = LinearLayoutManager(requireContext())
        list.list.adapter = adapter
        adapter.submitList(rows)
        list.empty.text = empty
        list.empty.isVisible = rows.isEmpty() && empty != null
        dialog.show()
    }

    private fun showSettings() {
        val current = prefs ?: ReaderPrefs()
        val sheet = SheetReaderSettingsBinding.inflate(LayoutInflater.from(requireContext()))
        sheet.fontSize.value = current.fontSizeSp.roundToInt().toFloat().coerceIn(sheet.fontSize.valueFrom, sheet.fontSize.valueTo)
        sheet.lineSpacing.value = (current.lineSpacing * 10).roundToInt().toFloat().coerceIn(sheet.lineSpacing.valueFrom, sheet.lineSpacing.valueTo)
        sheet.margins.value = (current.marginDp / 4 * 4).toFloat().coerceIn(sheet.margins.valueFrom, sheet.margins.valueTo)
        sheet.fontGroup.check(if (current.font == ReaderFont.SERIF) R.id.fontSerif else R.id.fontSans)
        sheet.themeGroup.check(
            when (theme(current)) {
                ReaderTheme.LIGHT -> R.id.themeLight
                ReaderTheme.SEPIA -> R.id.themeSepia
                ReaderTheme.DARK -> R.id.themeDark
            },
        )
        sheet.fontSize.addOnChangeListener { _, value, fromUser -> if (fromUser) viewModel.updatePrefs { it.copy(fontSizeSp = value) } }
        sheet.lineSpacing.addOnChangeListener { _, value, fromUser -> if (fromUser) viewModel.updatePrefs { it.copy(lineSpacing = value / 10f) } }
        sheet.margins.addOnChangeListener { _, value, fromUser -> if (fromUser) viewModel.updatePrefs { it.copy(marginDp = value.toInt()) } }
        sheet.fontGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) viewModel.updatePrefs { it.copy(font = if (id == R.id.fontSerif) ReaderFont.SERIF else ReaderFont.SANS) }
        }
        sheet.themeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val theme = when (id) {
                R.id.themeSepia -> ReaderTheme.SEPIA
                R.id.themeDark -> ReaderTheme.DARK
                else -> ReaderTheme.LIGHT
            }
            viewModel.updatePrefs { it.copy(theme = theme) }
        }
        BottomSheetDialog(requireContext()).apply {
            setContentView(sheet.root)
            show()
        }
    }

    private val menu = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) = menuInflater.inflate(R.menu.book_reader, menu)

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            when (menuItem.itemId) {
                R.id.action_toc -> showToc()
                R.id.action_bookmarks -> showBookmarks()
                R.id.action_add_bookmark -> addBookmark()
                R.id.action_reader_settings -> showSettings()
                else -> return false
            }
            return true
        }
    }

    override fun onDestroyView() {
        layoutJob?.cancel()
        binding = null
        paged = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_BOOK = "book"

        fun newInstance(bookId: Long) = BookReaderFragment().apply { arguments = bundleOf(ARG_BOOK to bookId) }
    }
}
