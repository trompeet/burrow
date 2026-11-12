package app.burrow

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kiwix.libkiwix.Library
import org.kiwix.libkiwix.Manager
import org.kiwix.libkiwix.Server
import java.io.File

/**
 * Development aid, not a regular test: serves a ZIM over HTTP from inside the device with libkiwix's own server
 * (the code behind kiwix-serve), so the Servers tab can be tried without a separate machine. See README.
 *
 *   adb shell am instrument -w -e class app.burrow.LocalKiwixServe -e zim /path/on/device.zim -e minutes 20 \
 *       app.burrow.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Then add http://127.0.0.1:8099 in the Servers tab.
 */
@RunWith(AndroidJUnit4::class)
class LocalKiwixServe {
    @Test
    fun serve() {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val zim = args.getString("zim") ?: return
        val minutes = args.getString("minutes")?.toLongOrNull() ?: 10
        context.container.ensureNative()
        assertTrue("Missing $zim", File(zim).canRead())

        val library = Library()
        Manager(library).addBookFromPath(zim, zim, "", false)
        val server = Server(library).apply {
            setAddress("127.0.0.1")
            setPort(args.getString("port")?.toIntOrNull() ?: 8099)
        }
        assertTrue("Server did not start", server.start())
        try {
            Thread.sleep(minutes * 60_000)
        } finally {
            server.stop()
        }
    }
}
