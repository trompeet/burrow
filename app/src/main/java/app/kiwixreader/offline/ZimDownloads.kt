package app.kiwixreader.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.kiwixreader.R
import app.kiwixreader.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Whole-ZIM downloads into app storage. Interrupted downloads resume with an HTTP Range request. */
object ZimDownloads {
    const val TAG = "zim-download"
    private const val TITLE_TAG = "title:"
    private const val FILE_TAG = "file:"

    fun directory(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "zim").apply { mkdirs() }

    fun enqueue(context: Context, url: String, title: String, fileName: String) {
        val request = OneTimeWorkRequestBuilder<ZimDownloadWorker>()
            .setInputData(workDataOf(ZimDownloadWorker.KEY_URL to url, ZimDownloadWorker.KEY_FILE to fileName, ZimDownloadWorker.KEY_TITLE to title))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(TITLE_TAG + title)
            .addTag(FILE_TAG + fileName)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(fileName, ExistingWorkPolicy.KEEP, request)
    }

    fun observe(context: Context): Flow<List<WorkInfo>> = WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG)

    fun title(info: WorkInfo) = info.tags.firstOrNull { it.startsWith(TITLE_TAG) }?.removePrefix(TITLE_TAG).orEmpty()

    private fun fileName(info: WorkInfo) = info.tags.firstOrNull { it.startsWith(FILE_TAG) }?.removePrefix(FILE_TAG)

    fun progress(info: WorkInfo): Pair<Long, Long> =
        info.progress.getLong(ZimDownloadWorker.KEY_DONE, 0) to info.progress.getLong(ZimDownloadWorker.KEY_TOTAL, -1)

    fun cancel(context: Context, info: WorkInfo) {
        WorkManager.getInstance(context).cancelWorkById(info.id)
        fileName(info)?.let { File(directory(context), "$it.part").delete() }
    }

    fun dismiss(context: Context) = WorkManager.getInstance(context).pruneWork()
}

class ZimDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val fileName = inputData.getString(KEY_FILE) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val directory = ZimDownloads.directory(applicationContext)
        val part = File(directory, "$fileName.part")
        val target = File(directory, fileName)

        // Foreground keeps long downloads alive; it may be refused when started from the background.
        runCatching { setForeground(foregroundInfo(title, -1)) }

        val client = applicationContext.container.http.client.newBuilder()
            .cache(null) // never push multi-GB files through the HTTP cache
            .followRedirects(true)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        try {
            val existing = if (part.exists()) part.length() else 0L
            val request = Request.Builder().url(url)
                .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 416 && existing > 0) return@use // already complete
                if (!response.isSuccessful) {
                    val transient = response.code == 408 || response.code == 429 || response.code >= 500
                    return@withContext if (transient && runAttemptCount < MAX_ATTEMPTS) {
                        Result.retry()
                    } else {
                        Result.failure(workDataOf(KEY_ERROR to "HTTP ${response.code}"))
                    }
                }
                val append = response.code == 206
                val offset = if (append) existing else 0L
                val length = response.body.contentLength()
                val total = if (length >= 0) offset + length else -1L
                var done = offset
                var lastReport = 0L
                FileOutputStream(part, append).use { out ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 500) {
                                lastReport = now
                                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                                if (total > 0) runCatching { setForeground(foregroundInfo(title, (done * 100 / total).toInt())) }
                            }
                        }
                    }
                }
                if (total > 0 && done < total) throw IOException("Connection closed early")
            }
            if (!part.renameTo(target)) return@withContext Result.failure(workDataOf(KEY_ERROR to "Cannot write file"))
            applicationContext.container.zimLibrary.addDownloaded(target)
            Result.success()
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "I/O error")))
        }
    }

    private fun foregroundInfo(title: String, percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.downloads), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(applicationContext.getString(R.string.downloading))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .build()
        val id = id.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_FILE = "file"
        const val KEY_TITLE = "title"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "downloads"
        private const val MAX_ATTEMPTS = 20
    }
}
