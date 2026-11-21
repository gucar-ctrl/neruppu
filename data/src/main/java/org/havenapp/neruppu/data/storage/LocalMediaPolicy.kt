package org.havenapp.neruppu.data.storage

/** A file in one of the local media folders, as seen by [LocalMediaPolicy]. */
data class LocalMediaFile(
    val path: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long
)

/**
 * Decides which local photo and audio files can be deleted.
 *
 * Telegram keeps every copy that was sent, so the phone only needs the files still waiting
 * for upload. Files that nothing waits for are leftovers and go first. If the folders are
 * still above [MAX_LOCAL_MEDIA_BYTES], the oldest waiting files go too.
 */
object LocalMediaPolicy {

    /** Upper bound for the photos and audio kept on the phone: 1 GB, counted in decimal bytes. */
    const val MAX_LOCAL_MEDIA_BYTES: Long = 1_000_000_000L

    /** Files written more recently than this may still be in use (capture or recording in progress). */
    const val GRACE_PERIOD_MS: Long = 5 * 60 * 1000L

    /** Returns the paths that can be deleted now. */
    fun filesToDelete(
        files: List<LocalMediaFile>,
        waitingPaths: Set<String>,
        nowMs: Long,
        maxBytes: Long = MAX_LOCAL_MEDIA_BYTES,
        gracePeriodMs: Long = GRACE_PERIOD_MS
    ): List<String> {
        val settled = files.filter { nowMs - it.lastModifiedMs >= gracePeriodMs }
        val leftovers = settled.filter { it.path !in waitingPaths }

        val toDelete = leftovers.mapTo(mutableListOf()) { it.path }
        var total = files.sumOf { it.sizeBytes } - leftovers.sumOf { it.sizeBytes }

        if (total > maxBytes) {
            val oldestWaiting = settled
                .filter { it.path in waitingPaths }
                .sortedBy { it.lastModifiedMs }
            for (file in oldestWaiting) {
                if (total <= maxBytes) break
                toDelete += file.path
                total -= file.sizeBytes
            }
        }
        return toDelete
    }
}
