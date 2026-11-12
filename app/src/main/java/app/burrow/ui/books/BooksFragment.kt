package app.burrow.ui.books

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import app.burrow.R
import app.burrow.books.BookFormat
import app.burrow.container
import app.burrow.data.BookEntity
import app.burrow.databinding.FragmentListBinding
import app.burrow.ui.common.Format
import app.burrow.ui.common.Row
import app.burrow.ui.common.RowAdapter
import app.burrow.ui.mainActivity
import app.burrow.ui.settings.SettingsFragment
import app.burrow.ui.setTitle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class BooksFragment : Fragment(R.layout.fragment_list) {
    private var binding: FragmentListBinding? = null

    private val importBooks = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        val app = container
        app.appScope.launch {
            val failed = uris.count { uri -> runCatching { app.books.import(uri) }.isFailure }
            if (failed > 0) {
                withContext(Dispatchers.Main) {
                    if (isAdded) snackbar(resources.getQuantityString(R.plurals.import_failed, failed, failed))
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentListBinding.bind(view).also { binding = it }
        setTitle(getString(R.string.tab_books))
        val adapter = RowAdapter(
            onClick = { row -> (row.payload as? BookEntity)?.let { mainActivity.push(BookReaderFragment.newInstance(it.id)) } },
            onLongClick = { row -> (row.payload as? BookEntity)?.let(::confirmDelete) },
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.emptyText.setText(R.string.books_empty)
        binding.emptyButton.setText(R.string.import_books)
        binding.emptyButton.setOnClickListener { launchImport() }
        requireActivity().addMenuProvider(menu, viewLifecycleOwner, Lifecycle.State.RESUMED)

        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.books.books.collect { books ->
                    adapter.submitList(
                        books.map { book ->
                            val format = runCatching { BookFormat.valueOf(book.format) }.getOrNull()
                            Row(
                                key = "book:${book.id}",
                                title = book.title,
                                subtitle = book.author.ifBlank { null },
                                detail = Format.joined(
                                    format?.extension?.uppercase(),
                                    Format.size(context, book.sizeBytes),
                                    if (book.lastOpenedAt > 0) getString(R.string.percent_read, (book.progress * 100).roundToInt()) else null,
                                ),
                                icon = R.drawable.ic_book,
                                progress = if (book.lastOpenedAt > 0) (book.progress * 100).roundToInt() else null,
                                payload = book,
                            )
                        },
                    )
                    binding.empty.isVisible = books.isEmpty()
                }
            }
        }
    }

    private fun launchImport() = importBooks.launch(
        arrayOf("application/epub+zip", "text/plain", "text/html", "application/xhtml+xml", "application/octet-stream"),
    )

    private fun confirmDelete(book: BookEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.delete_book_question, book.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                val app = container
                app.appScope.launch { app.books.delete(book) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun snackbar(message: String) {
        binding?.root?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
    }

    private val menu = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) = menuInflater.inflate(R.menu.books, menu)

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            when (menuItem.itemId) {
                R.id.action_import -> launchImport()
                R.id.action_settings -> mainActivity.push(SettingsFragment())
                else -> return false
            }
            return true
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
