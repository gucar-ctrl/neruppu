package org.havenapp.neruppu.data.storage

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.havenapp.neruppu.data.local.dao.EventDao
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the local photo and audio folders within [LocalMediaPolicy.MAX_LOCAL_MEDIA_BYTES].
 *
 * Runs after each saved file (at most once a minute) and when the monitoring service starts.
 * Never throws: a failed cleanup must not make a capture fail.
 */
@Singleton
class LocalMediaJanitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventDao: EventDao
) {
    private val lastRunMs = AtomicLong(0L)

    suspend fun enforce(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRunMs.get() < MIN_INTERVAL_MS) return
        lastRunMs.set(now)
        try {
            withContext(Dispatchers.IO) {
                val files = MEDIA_FOLDERS
                    .flatMap { folder ->
                        File(context.filesDir, folder).listFiles()?.filter { it.isFile }.orEmpty()
                    }
                    .map { LocalMediaFile(it.absolutePath, it.length(), it.lastModified()) }
                val waiting = eventDao.getWaitingMediaPaths().toSet()
                val doomed = LocalMediaPolicy.filesToDelete(files, waiting, now)
                doomed.forEach { File(it).delete() }
                if (doomed.isNotEmpty()) {
                    Log.d(TAG, "Removed ${doomed.size} local media files")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Local media cleanup failed", e)
        }
    }

    private companion object {
        const val TAG = "LocalMediaJanitor"
        const val MIN_INTERVAL_MS = 60_000L
        val MEDIA_FOLDERS = listOf("captures", "audio_captures")
    }
}
