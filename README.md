<img src="docs/icon.png" alt="Burrow icon" width="96" align="right">

# Burrow

A native Android app (Kotlin) for reading Kiwix ZIM files, browsing remote `kiwix-serve` instances (with a page
cache and offline copies), and reading EPUB/TXT/HTML books such as Project Gutenberg titles.

Three tabs in one activity:

| Tab | What it does |
| --- | --- |
| **Library** | ZIM files found in folders you pick, active ZIM downloads, and articles saved for offline. |
| **Servers** | kiwix-serve instances you add (optional display name, optional HTTP basic auth), with a live status per row and each server's OPDS catalog. |
| **Books** | Imported EPUB, TXT and HTML books in a paginated e-reader. |

The look follows the official kiwix-android app: Kiwix palette (`denim_blue800` primary, `alabaster_white` /
`mine_shaft_gray900` surfaces, black app and bottom bars), flat lists, no elevation, light and dark theme from the
system setting. It uses XML layouts with **Material Components** (`Theme.MaterialComponents.DayNight`). There is no
Material 3, no Compose UI and no dynamic colour.

## Install

Download an APK from the [latest release](https://github.com/trompeet/burrow/releases/latest):

| APK | For |
| --- | --- |
| `burrow-<version>-arm64-v8a.apk` | Almost all phones and tablets from the last ~8 years |
| `burrow-<version>-armeabi-v7a.apk` | Older 32-bit ARM devices |
| `burrow-<version>-x86_64.apk` / `-x86.apk` | Emulators and x86 Chromebooks |

Requires Android 8.0 or later. Open the downloaded file and allow your browser or file manager to install apps
when Android asks. Releases are signed with the same key, so later versions install as updates; check the
`SHA256SUMS` file attached to each release if you want to verify the download.

## Build

Requirements: JDK 17+ (21 tested), Android SDK with platform 37 (Android 17), build-tools 36+. The Gradle wrapper
downloads Gradle 9.9.1.

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties   # or set ANDROID_HOME
./gradlew assembleDebug
```

libkiwix ships about 50 MB of native code per ABI, so the build produces one APK per ABI in
`app/build/outputs/apk/debug/`: `app-arm64-v8a-debug.apk` for most phones, `app-x86_64-debug.apk` for the emulator.

```bash
adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Signed release APKs (`app/build/outputs/apk/release/`) need a signing properties file outside the repository,
by default `~/.android/burrow-release.properties`, or pass `-Pburrow.signing=/path/to/file`:

```properties
storeFile=/path/to/burrow-release.jks
storePassword=…
keyAlias=burrow
keyPassword=…
```

```bash
./gradlew assembleRelease
```

Without that file, `assembleRelease` produces unsigned APKs.

Unit tests (JVM, no device needed):

```bash
./gradlew testDebugUnitTest
```

No Material 3 artifact in the dependency graph:

```bash
./gradlew :app:dependencies | grep -i material3   # prints nothing
```

The only `androidx.compose` artifact in the graph is `androidx.compose.runtime:runtime-annotation`, a set of
stability annotations that AndroidX libraries depend on. It contains no Compose UI and no Material.

### Trying the Servers tab without a separate machine

`app/src/androidTest/.../LocalKiwixServe.kt` is a development aid, not a regular test. It serves a ZIM on
`127.0.0.1:8099` inside the device, using libkiwix's own HTTP server (the code behind kiwix-serve):

```bash
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e class app.burrow.LocalKiwixServe -e zim /sdcard/Android/data/app.burrow/files/zim/some.zim -e minutes 20 app.burrow.test/androidx.test.runner.AndroidJUnitRunner
```

Then add `http://127.0.0.1:8099` in the Servers tab. Use a ZIM in the app's own storage (see "Storage access").
A real `kiwix-serve` on your LAN works the same way: `kiwix-serve -p 8080 *.zim`, then add `http://<ip>:8080`.

## Architecture

```
app/src/main/java/app/burrow/
  BurrowApp.kt     Application + AppContainer (manual DI, native lib loading, ICU data)
  data/                 Room entities/DAOs (library, servers, bookmarks, offline index, books), DataStore settings
  net/                  HttpStack (OkHttp + disk cache + basic auth), CachePolicy, RemoteFetcher, cookie bridge
  remote/               kiwix-serve API URLs, OPDS parser, search-results parser, ServerRepository
  zim/                  ZimArchive (libkiwix wrapper), ZimLibrary (SAF folder scanning)
  offline/              OfflineStore ("Save for offline"), ZimDownloads (WorkManager, resumable)
  books/                EPUB/TXT/HTML parsing, Gutenberg stripping, Paginator, BookRepository
  ui/                   MainActivity, the three tabs, article reader, book reader, settings
```

* **ZIM reading** goes through libkiwix's Android bindings (`org.kiwix:libkiwix:2.7.0`, the same artifact and
  version as kiwix-android). Pages are served to a WebView from a reserved, never-resolving host
  (`https://zim.burrow.invalid/…`). Any request to another host is blocked, so a local ZIM causes no network
  traffic. Search uses the full-text index when the ZIM has one and title suggestions otherwise.
* **kiwix-serve endpoints** (see kiwix-tools `docs/kiwix-serve.rst`): the OPDS v2 catalog
  `/catalog/v2/entries?start=&count=&q=` (paged, 50 per page, server-side search) and full-text search
  `/search?books.name=&pattern=&format=xml`. Articles load from each book's `text/html` OPDS link
  (`<root>/content/<book>`), which may sit on a separate content host (`--contentServerURL`). A URL root such as
  `http://host/kiwix` is supported.
* **Remote reader traffic**: every WebView request goes through `RemoteFetcher`. Redirects are followed hop by hop,
  and each hop must be on the server's origin or the book's content origin; anything else is blocked before a
  request is made.
* **Page cache**: OkHttp's disk cache (DiskLruCache, LRU eviction), 500 MB by default, configurable in Settings,
  which also show usage and a "Clear cache" button. kiwix-serve marks content `must-revalidate`, which would forbid
  serving stale pages offline. `CachePolicy` keeps each response's freshness lifetime but drops the directives that
  forbid stale use. The fetcher shows a cached page at once and revalidates it in the background with a
  conditional request when online.
* **Offline reading**: "Save for offline" stores the page and its images, stylesheets, scripts and fonts (including
  those referenced from CSS) under app storage and records every URL in the Room offline index. The fetcher checks
  that index first, so saved articles open without network. A page that is neither saved nor cached shows "This
  article is not available offline".
* **Whole-ZIM download** (optional feature): long-press a catalog entry that has an acquisition link. A WorkManager
  foreground worker downloads it into app storage, shows progress in the Library tab, and resumes after
  interruption with an HTTP `Range` request. If the file is hosted somewhere other than your server (for example
  `download.kiwix.org`), the confirmation dialog names that host first.
* **E-reader**: books are converted to styled text and split into sections (one per chapter or TOC target, at most
  30,000 characters). Each section is laid out with `StaticLayout`, and `Paginator` cuts it into pages of whole
  lines. The position is stored as (section, character offset), so it survives font, margin and size changes, and it
  is written to Room on every page turn, so it survives the app being killed. Tap the left or right 30 % of the
  screen or swipe to turn pages, and tap the middle to hide the toolbar. TalkBack users can turn pages with scroll
  actions.

## Storage access

The app picks folders through the Storage Access Framework, as specified. However, **libzim always reopens the file
by path** (`/dev/fd/<n>`; see openzim/libzim `src/file_part.h` and `src/fs_unix.cpp`). On Android 11 and later,
that reopen is refused for files in shared storage that the app reaches only through a SAF grant. kiwix-android
deals with the same limitation (kiwix/kiwix-android#3636). I also tried wrapping the descriptor with
`StorageManager.openProxyFileDescriptor`, but Android refuses that reopen as well.

So ZIM files in a picked folder are still found and listed. When one cannot be read in place, its row says **Needs
file access**, and tapping it offers two options:

1. **Copy to app** copies the file into the app's own storage (`Android/data/app.burrow/files/zim`), which
   libkiwix can read. No extra permission is needed, but the file then takes up space twice.
2. **Allow access** opens Android's "All files access" setting (`MANAGE_EXTERNAL_STORAGE`; on Android 8–10, the
   legacy storage permission). ZIMs are then opened in place by their real path, as kiwix-android's
   non-Play-Store build does.

Both permissions are optional and requested only when you choose them. ZIMs that are downloaded, or copied into the
app folder over USB, always work.

## Privacy

* No accounts, analytics, ads or tracking. No Google services.
* WebView Safe Browsing and WebView metrics are turned off in the manifest, because Safe Browsing would send URLs
  to Google.
* Network traffic goes only to servers you add. The two exceptions need your explicit action: a ZIM download link
  hosted elsewhere (you confirm a dialog naming the host), and external links in articles (you confirm before they
  open in another app).
* Cleartext HTTP is allowed, because kiwix-serve usually runs on a LAN over `http://`. When you enter a password for
  an `http://` server, the dialog warns that it will be sent unencrypted.
* Server passwords are stored in the app's private Room database. Backups and device-to-device transfer are turned
  off (`allowBackup=false` plus data-extraction rules), so they are not copied off the device.

## Assumptions and decisions

* **Name and IDs**: "Burrow" (kiwis live in burrows; this is a burrow for your offline library), application ID
  `app.burrow`. "Kiwix" is the official project's name, so it is not used in the app name or package.
* **SDK**: minSdk 26, compile and target SDK 37 (Android 17, the latest stable). AGP 9.5.1 with built-in Kotlin
  2.4.21, KSP 2.3.13, Gradle 9.9.1.
* **UI stack**: XML layouts with ViewBinding and Material Components 1.14 (pre-M3 themes only), and fragments in a
  single activity. Detail screens (catalog, readers, settings) are pushed on top of the current tab and hide the
  bottom bar.
* **Palette**: copied from kiwix-android `core/src/main/res/values/colors.xml` and its `themes.xml`, including black
  toolbar and bottom navigation and the same selected/unselected tint. The e-reader's light and dark page colours
  reuse the Kiwix surfaces; sepia is the only new colour pair. Icons are Material icon paths. The launcher icon is a
  generic library glyph, not the Kiwix logo.
* **ICU data**: `assets/icu/icudt58l.dat` is the same ICU data file kiwix-android ships. libkiwix needs it for
  search tokenisation.
* **Dependencies** beyond those requested: jsoup (HTML and EPUB parsing, finding assets to save offline),
  WorkManager (resumable background downloads), kotlinx-coroutines. Tests use MockWebServer and kxml2 (a real
  XmlPullParser for JVM tests). No DI framework: there is one hand-written container.
* **Catalog**: books are listed without thumbnails, and a book is opened by tapping it and downloaded by
  long-pressing it.
* **Remote search** uses kiwix-serve's full-text endpoint, so it returns nothing for books without a full-text index.
* **Offline save** captures what the page references statically. Content that scripts load later is not saved.
* **Downloads** keep the server's file name and are stored under `Android/data/app.burrow/files/zim`.
* **E-reader**: images in books are skipped (text only). EPUB tables of contents come from the EPUB 3 nav document,
  then the NCX, then the headings. TXT chapters are detected from heading lines ("CHAPTER I.", "Book Two", "Stave
  III" and similar). Hard-wrapped lines are joined into paragraphs, while blocks of short or indented lines (verse,
  contents lists) keep their line breaks. Reading settings (font size, serif/sans, line spacing, margins, theme) are
  global, not per book. Until you choose a theme, the reader follows the system's light/dark setting.
* **Gutenberg**: TXT files are cut at the `*** START OF` and `*** END OF` markers, including older variants and
  markers wrapped over two lines. HTML and EPUB files have the `#pg-header`, `#pg-footer` and `.pg-boilerplate`
  sections removed, and documents before the header or after the footer are dropped. Imported books are copied into
  app storage, so the originals can be deleted.
* **Database**: schema version 1, exported to `app/schemas/`. There is no migration yet; add one before changing
  the schema after a release.
* **Licence**: GPL-3.0-or-later (see `LICENSE`), matching libkiwix, which the app links.

## Verification

Run on an Android 16 (API 36) x86_64 emulator:

* `./gradlew assembleDebug` succeeds, and `./gradlew :app:dependencies` contains no material3 artifact.
* All 42 unit tests pass: cache policy and stale-while-revalidate (MockWebServer), LRU trimming, basic-auth scoping,
  OPDS and search parsing, kiwix-serve URLs, Gutenberg stripping, pagination, plain-text reflow.
* A local ZIM opens after "Copy to app" and after granting all-files access. Links, back/forward and search work.
* kiwix-serve (libkiwix's server inside the emulator, and library.kiwix.org for the catalog): status ("Online ·
  3657 books"), paged catalog, catalog search and remote reading work. An unreachable server shows "Unreachable"
  without crashing.
* An article saved for offline opens in airplane mode with the server stopped. A page that was never fetched shows
  "Not available offline".
* A Gutenberg EPUB (Pride and Prejudice) opens with the licence removed and a working TOC, and it reopens on the same
  page after a force-stop. A Gutenberg TXT (Alice in Wonderland) opens with the header removed.

Not verified: the whole-ZIM download (libkiwix's test server publishes no download link, and I did not download a
large file), basic auth against a real password-protected server (covered only by a unit test), a physical device,
and Android 8–10.

library.kiwix.org serves its article pages behind an anti-crawler confirmation page. The app shares cookies
between OkHttp and the WebView so you can pass it yourself, but I did not do so in testing.

## Licence

Burrow is free software, licensed under the GNU General Public License, version 3 or (at your option) any later
version. See [LICENSE](LICENSE).

It uses libkiwix/libzim (GPL-3.0), ICU data (`assets/icu/icudt58l.dat`, Unicode licence), OkHttp, jsoup and
AndroidX/Material Components (Apache-2.0 or MIT). The colour palette follows the kiwix-android app. Burrow is not
affiliated with or endorsed by the Kiwix project.
