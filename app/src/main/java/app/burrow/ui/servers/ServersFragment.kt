package app.burrow.ui.servers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import app.burrow.R
import app.burrow.container
import app.burrow.data.ServerEntity
import app.burrow.databinding.DialogServerBinding
import app.burrow.databinding.FragmentListBinding
import app.burrow.remote.KiwixServe
import app.burrow.remote.ServerStatus
import app.burrow.ui.common.Row
import app.burrow.ui.common.RowAdapter
import app.burrow.ui.mainActivity
import app.burrow.ui.settings.SettingsFragment
import app.burrow.ui.setTitle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ServersFragment : Fragment(R.layout.fragment_list) {
    private val statuses = MutableStateFlow<Map<Long, ServerStatus>>(emptyMap())
    private var servers: List<ServerEntity> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentListBinding.bind(view)
        setTitle(getString(R.string.tab_servers))
        val adapter = RowAdapter(
            onClick = { row -> (row.payload as? ServerEntity)?.let { mainActivity.push(CatalogFragment.newInstance(it.id)) } },
            onLongClick = { row -> (row.payload as? ServerEntity)?.let(::showActions) },
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.emptyText.setText(R.string.servers_empty)
        binding.emptyButton.setText(R.string.add_server)
        binding.emptyButton.setOnClickListener { editServer(null) }
        requireActivity().addMenuProvider(menu, viewLifecycleOwner, Lifecycle.State.RESUMED)

        val repository = container.servers
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var checked = emptySet<Long>()
                launch {
                    repository.servers.collect { list ->
                        servers = list
                        // Probe new servers; refresh all when the screen comes back.
                        list.filter { it.id !in checked }.forEach { check(it) }
                        checked = list.map { it.id }.toSet()
                    }
                }
                combine(repository.servers, statuses) { list, status -> list.map { row(it, status[it.id]) } }
                    .collect { rows ->
                        adapter.submitList(rows)
                        binding.empty.isVisible = rows.isEmpty()
                    }
            }
        }
    }

    private fun row(server: ServerEntity, status: ServerStatus?): Row {
        val (text, color) = when (status) {
            null, ServerStatus.Checking -> getString(R.string.status_checking) to 0
            is ServerStatus.Online -> (
                status.bookCount?.let { resources.getQuantityString(R.plurals.status_online_books, it, it) }
                    ?: getString(R.string.status_online)
                ) to R.attr.statusOnline
            ServerStatus.AuthRequired -> getString(R.string.status_auth) to R.attr.statusOffline
            is ServerStatus.HttpError -> getString(R.string.status_http_error, status.code) to R.attr.statusOffline
            ServerStatus.NotKiwix -> getString(R.string.status_not_kiwix) to R.attr.statusOffline
            is ServerStatus.Unreachable -> getString(R.string.status_unreachable) to R.attr.statusOffline
        }
        return Row(
            key = "server:${server.id}",
            title = server.displayName,
            subtitle = if (server.name.isBlank()) null else server.baseUrl,
            status = text,
            statusColor = color,
            icon = R.drawable.ic_dns,
            payload = server,
        )
    }

    private fun check(server: ServerEntity) {
        statuses.update { it + (server.id to ServerStatus.Checking) }
        viewLifecycleOwner.lifecycleScope.launch {
            val status = container.servers.checkStatus(server)
            statuses.update { it + (server.id to status) }
        }
    }

    private fun showActions(server: ServerEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(server.displayName)
            .setItems(arrayOf(getString(R.string.edit), getString(R.string.delete))) { _, which ->
                if (which == 0) {
                    editServer(server)
                } else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage(getString(R.string.delete_server_question, server.displayName))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            val app = container
                            app.appScope.launch { app.servers.delete(server) }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun editServer(existing: ServerEntity?) {
        val form = DialogServerBinding.inflate(LayoutInflater.from(requireContext()))
        existing?.let {
            form.url.setText(it.baseUrl)
            form.name.setText(it.name)
            form.username.setText(it.username)
            form.password.setText(it.password)
        }
        fun updateHint() {
            val insecure = form.url.text.toString().trim().let { !it.startsWith("https://", ignoreCase = true) }
            form.cleartextWarning.isVisible = insecure && !form.username.text.isNullOrEmpty()
        }
        form.url.doAfterTextChanged { form.urlLayout.error = null; updateHint() }
        form.username.doAfterTextChanged { updateHint() }
        updateHint()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) R.string.add_server else R.string.edit_server)
            .setView(form.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url = KiwixServe.normalizeBaseUrl(form.url.text.toString())
            if (url == null) {
                form.urlLayout.error = getString(R.string.invalid_url)
                return@setOnClickListener
            }
            val server = ServerEntity(
                id = existing?.id ?: 0,
                baseUrl = KiwixServe.baseUrlString(url),
                name = form.name.text.toString().trim(),
                username = form.username.text.toString().trim().ifEmpty { null },
                password = form.password.text.toString().ifEmpty { null },
            )
            val app = container
            viewLifecycleOwner.lifecycleScope.launch {
                val id = app.servers.save(server)
                check(server.copy(id = if (server.id == 0L) id else server.id))
            }
            dialog.dismiss()
        }
    }

    private val menu = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) = menuInflater.inflate(R.menu.servers, menu)

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            when (menuItem.itemId) {
                R.id.action_add_server -> editServer(null)
                R.id.action_refresh -> servers.forEach(::check)
                R.id.action_settings -> mainActivity.push(SettingsFragment())
                else -> return false
            }
            return true
        }
    }
}
