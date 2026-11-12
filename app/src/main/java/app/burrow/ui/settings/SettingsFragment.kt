package app.burrow.ui.settings

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.burrow.BuildConfig
import app.burrow.R
import app.burrow.container
import app.burrow.data.Settings
import app.burrow.databinding.FragmentSettingsBinding
import app.burrow.ui.common.Format
import app.burrow.ui.setTitle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment(R.layout.fragment_settings) {
    private var binding: FragmentSettingsBinding? = null
    private var limitMb = Settings.DEFAULT_CACHE_MB

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSettingsBinding.bind(view).also { binding = it }
        setTitle(getString(R.string.settings))
        binding.version.text = getString(R.string.version, BuildConfig.VERSION_NAME)
        binding.cacheLimitRow.setOnClickListener { chooseLimit() }
        binding.clearCache.setOnClickListener { confirmClear() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.settings.cacheSizeMb.collect {
                    limitMb = it
                    binding.cacheLimit.text = Format.size(requireContext(), it * MB)
                    refreshUsage()
                }
            }
        }
    }

    private fun refreshUsage() {
        val app = container
        viewLifecycleOwner.lifecycleScope.launch {
            val (cache, offline) = withContext(Dispatchers.IO) {
                runCatching { app.http.cache.size() }.getOrDefault(0L) to app.offline.usedBytes()
            }
            val binding = binding ?: return@launch
            val context = requireContext()
            binding.cacheUsage.text = getString(R.string.cache_usage_value, Format.size(context, cache), Format.size(context, limitMb * MB))
            binding.offlineUsage.text = Format.size(context, offline)
        }
    }

    private fun chooseLimit() {
        val choices = Settings.CACHE_SIZE_CHOICES_MB
        val labels = choices.map { Format.size(requireContext(), it * MB) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cache_limit)
            .setSingleChoiceItems(labels, choices.indexOf(limitMb)) { dialog, which ->
                val app = container
                app.appScope.launch { app.settings.setCacheSizeMb(choices[which]) }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.clear_cache_question)
            .setPositiveButton(R.string.clear_cache) { _, _ ->
                val app = container
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { runCatching { app.http.cache.evictAll() } }
                    refreshUsage()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val MB = 1_000_000L // decimal, matching how Android formats sizes
    }
}
