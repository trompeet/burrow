package app.burrow.ui.library

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
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
import androidx.work.WorkInfo
import app.burrow.R
import app.burrow.container
import app.burrow.data.OfflineArticleEntity
import app.burrow.data.ZimFileEntity
import app.burrow.data.ZimFolderEntity
import app.burrow.databinding.FragmentListBinding
import app.burrow.offline.ZimDownloads
import app.burrow.ui.common.Format
import app.burrow.ui.common.Row
import app.burrow.ui.common.RowAdapter
import app.burrow.ui.mainActivity
import app.burrow.ui.reader.ArticleReaderFragment
import app.burrow.ui.settings.SettingsFragment
import app.burrow.ui.setTitle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryFragment : Fragment(R.layout.fragment_list) {
    private var binding: FragmentListBinding? = null
    private var folders: List<ZimFolderEntity> = emptyList()

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val library = container.zimLibrary
        // App scope: a long scan keeps running if the user leaves the tab.
        container.appScope.launch {
            val result = runCatching { library.addFolder(uri) }
            if (result.isFailure) {
                withContext(Dispatchers.Main) { if (isAdded) showMessage(getString(R.string.folder_add_failed)) }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentListBinding.bind(view).also { binding = it }
        setTitle(getString(R.string.tab_library))
        val adapter = RowAdapter(::onClick, ::onLongClick)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.emptyText.setText(R.string.library_empty)
        binding.emptyButton.setText(R.string.add_folder)
        binding.emptyButton.setOnClickListener { pickFolder.launch(null) }

        requireActivity().addMenuProvider(menu, viewLifecycleOwner, Lifecycle.State.RESUMED)

        val app = container
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.zimLibrary.folders.collect { folders = it } }
                launch { app.zimLibrary.scanning.collect { binding.loading.isVisible = it } }
                combine(
                    app.zimLibrary.files,
                    ZimDownloads.observe(requireContext()),
                    app.offline.articles,
                ) { files, downloads, offline -> rows(files, downloads, offline) }
                    .collect { rows ->
                        adapter.submitList(rows)
                        binding.empty.isVisible = rows.isEmpty()
                    }
            }
        }
    }

    private fun rows(files: List<ZimFileEntity>, downloads: List<WorkInfo>, offline: List<OfflineArticleEntity>): List<Row> {
        val context = requireContext()
        val rows = ArrayList<Row>()
        if (files.isNotEmpty()) {
            rows += Row.header("zim", getString(R.string.section_zim_files))
            files.forEach { file ->
                if (!file.readable) {
                    rows += Row(
                        key = "zim:${file.uri}",
                        title = file.title,
                        subtitle = Format.size(context, file.sizeBytes),
                        status = getString(R.string.needs_file_access),
                        statusColor = R.attr.statusOffline,
                        icon = R.drawable.ic_library,
                        payload = file,
                    )
                    return@forEach
                }
                rows += Row(
                    key = "zim:${file.uri}",
                    title = file.title,
                    subtitle = Format.joined(
                        Format.language(file.language),
                        resources.getQuantityString(R.plurals.article_count, file.articleCount, Format.count(file.articleCount)),
                    ),
                    detail = Format.joined(
                        Format.size(context, file.sizeBytes),
                        if (file.folderUri == null) getString(R.string.downloaded) else null,
                    ),
                    icon = R.drawable.ic_library,
                    iconBytes = file.icon,
                    payload = file,
                )
            }
        }
        val active = downloads.filter { !it.state.isFinished || it.state == WorkInfo.State.FAILED }
        if (active.isNotEmpty()) {
            rows += Row.header("downloads", getString(R.string.downloads))
            active.forEach { info ->
                val (done, total) = ZimDownloads.progress(info)
                val status = when (info.state) {
                    WorkInfo.State.ENQUEUED -> getString(R.string.download_waiting)
                    WorkInfo.State.FAILED -> getString(R.string.download_failed)
                    else -> if (total > 0) "${Format.size(context, done)} / ${Format.size(context, total)}" else Format.size(context, done)
                }
                rows += Row(
                    key = "download:${info.id}",
                    title = ZimDownloads.title(info),
                    status = status,
                    statusColor = if (info.state == WorkInfo.State.FAILED) R.attr.statusOffline else 0,
                    icon = R.drawable.ic_download,
                    progress = when {
                        info.state != WorkInfo.State.RUNNING -> null
                        total > 0 -> (done * 100 / total).toInt()
                        else -> -1
                    },
                    payload = info,
                )
            }
        }
        if (offline.isNotEmpty()) {
            rows += Row.header("offline", getString(R.string.section_offline))
            offline.forEach { article ->
                rows += Row(
                    key = "offline:${article.id}",
                    title = article.title,
                    subtitle = article.bookTitle,
                    detail = Format.joined(Format.date(context, article.savedAt), Format.size(context, article.sizeBytes)),
                    icon = R.drawable.ic_article,
                    payload = article,
                )
            }
        }
        return rows
    }

    private fun onClick(row: Row) {
        when (val item = row.payload) {
            is ZimFileEntity -> if (item.readable) {
                mainActivity.push(ArticleReaderFragment.forZim(item.uri, item.title))
            } else {
                offerAccess(item)
            }
            is OfflineArticleEntity -> mainActivity.push(ArticleReaderFragment.forOffline(item))
            is WorkInfo -> onLongClick(row)
        }
    }

    private fun onLongClick(row: Row) {
        val context = requireContext()
        val app = container
        when (val item = row.payload) {
            is ZimFileEntity -> if (item.folderUri == null) {
                confirm(getString(R.string.delete_downloaded_zim, item.title)) {
                    app.appScope.launch { app.zimLibrary.deleteDownloaded(item) }
                }
            } else {
                val folder = folders.firstOrNull { it.treeUri == item.folderUri } ?: return
                confirm(getString(R.string.remove_folder_question, folder.displayName)) {
                    app.appScope.launch { app.zimLibrary.removeFolder(folder) }
                }
            }
            is OfflineArticleEntity -> confirm(getString(R.string.delete_offline_question, item.title)) {
                app.appScope.launch { app.offline.delete(item) }
            }
            is WorkInfo -> if (item.state.isFinished) {
                ZimDownloads.dismiss(context)
            } else {
                confirm(getString(R.string.cancel_download_question, ZimDownloads.title(item))) {
                    ZimDownloads.cancel(context, item)
                }
            }
        }
    }

    /** libkiwix cannot read SAF-only files in shared storage; let the user pick how to make this one readable. */
    private fun offerAccess(file: ZimFileEntity) {
        val app = container
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.needs_file_access)
            .setMessage(getString(R.string.needs_file_access_explanation, Format.size(requireContext(), file.sizeBytes)))
            .setPositiveButton(R.string.copy_to_app_storage) { _, _ ->
                showMessage(getString(R.string.copying))
                app.appScope.launch {
                    val result = runCatching { app.zimLibrary.copyToAppStorage(file) }
                    withContext(Dispatchers.Main) {
                        if (isAdded) showMessage(getString(if (result.isSuccess) R.string.copy_done else R.string.copy_failed))
                    }
                }
            }
            .setNeutralButton(R.string.allow_all_files_access) { _, _ -> requestAllFilesAccess() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            awaitingAccess = true
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${requireContext().packageName}"))
            runCatching { startActivity(intent) }.onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            requestLegacyPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private var awaitingAccess = false

    private val requestLegacyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) rescan()
    }

    override fun onResume() {
        super.onResume()
        if (awaitingAccess) {
            awaitingAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) rescan()
        }
    }

    private fun rescan() {
        val app = container
        app.appScope.launch { app.zimLibrary.rescanAll() }
    }

    private fun manageFolders() {
        if (folders.isEmpty()) {
            pickFolder.launch(null)
            return
        }
        val names = folders.map { it.displayName }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.folders)
            .setItems(names) { _, which ->
                val folder = folders[which]
                confirm(getString(R.string.remove_folder_question, folder.displayName)) {
                    val app = container
                    app.appScope.launch { app.zimLibrary.removeFolder(folder) }
                }
            }
            .setPositiveButton(R.string.add_folder) { _, _ -> pickFolder.launch(null) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirm(message: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> action() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMessage(message: String) {
        binding?.root?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
    }

    private val menu = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) = menuInflater.inflate(R.menu.library, menu)

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            when (menuItem.itemId) {
                R.id.action_add_folder -> pickFolder.launch(null)
                R.id.action_rescan -> rescan()
                R.id.action_folders -> manageFolders()
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
