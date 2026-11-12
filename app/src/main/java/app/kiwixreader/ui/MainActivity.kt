package app.kiwixreader.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import app.kiwixreader.R
import app.kiwixreader.databinding.ActivityMainBinding
import app.kiwixreader.ui.books.BooksFragment
import app.kiwixreader.ui.library.LibraryFragment
import app.kiwixreader.ui.servers.ServersFragment

/** Single activity: three tabs (Library, Servers, Books); readers and detail screens are pushed on top. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var bars = Insets.NONE
    private var chromeVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        // App bar and bottom bar are black in both themes (as in kiwix-android), so system bar icons stay light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            bars = Insets.of(system.left, system.top, system.right, maxOf(system.bottom, ime.bottom))
            applyInsets()
            insets
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        binding.bottomNav.setOnItemReselectedListener { popToRoot() }
        supportFragmentManager.addOnBackStackChangedListener { updateChrome() }
        if (savedInstanceState == null) showTab(R.id.tab_library)
        updateChrome()
    }

    private fun showTab(id: Int) {
        popToRoot()
        val fragment: Fragment = when (id) {
            R.id.tab_servers -> ServersFragment()
            R.id.tab_books -> BooksFragment()
            else -> LibraryFragment()
        }
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.container, fragment)
        }
    }

    private fun popToRoot() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
    }

    /** Opens a screen above the current tab. */
    fun push(fragment: Fragment) {
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.container, fragment)
            addToBackStack(null)
        }
    }

    /** Readers can hide the app bar for distraction-free reading. */
    fun setChromeVisible(visible: Boolean) {
        chromeVisible = visible
        binding.appBar.isVisible = visible
        applyInsets()
    }

    val isChromeVisible get() = chromeVisible

    private fun updateChrome() {
        val nested = supportFragmentManager.backStackEntryCount > 0
        binding.bottomNav.isVisible = !nested
        supportActionBar?.setDisplayHomeAsUpEnabled(nested)
        setChromeVisible(true)
    }

    private fun applyInsets() {
        binding.appBar.setPadding(0, bars.top, 0, 0)
        val top = if (binding.appBar.isVisible) 0 else bars.top
        val bottom = if (binding.bottomNav.isVisible) 0 else bars.bottom
        binding.root.setPadding(bars.left, top, bars.right, bottom)
    }
}

val Fragment.mainActivity: MainActivity get() = requireActivity() as MainActivity

fun Fragment.setTitle(title: CharSequence?, subtitle: CharSequence? = null) {
    mainActivity.supportActionBar?.let {
        it.title = title
        it.subtitle = subtitle
    }
}
