package org.havenapp.neruppu.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaPolicyTest {

    private val now = 10_000_000L
    private val old = now - 3_600_000L
    private val fresh = now - 1_000L

    private fun file(path: String, size: Long, modified: Long) = LocalMediaFile(path, size, modified)

    @Test
    fun `leftovers are removed even when the folders are under the cap`() {
        val files = listOf(file("orphan.jpg", 100, old), file("waiting.jpg", 400, old))

        val result = LocalMediaPolicy.filesToDelete(files, setOf("waiting.jpg"), now, maxBytes = 1_000)

        assertEquals(listOf("orphan.jpg"), result)
    }

    @Test
    fun `files written recently are never removed`() {
        val files = listOf(file("in-progress.mp4", 5_000, fresh))

        val result = LocalMediaPolicy.filesToDelete(files, emptySet(), now, maxBytes = 1_000)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `waiting files are kept while under the cap`() {
        val files = listOf(file("a.jpg", 400, old), file("b.jpg", 400, old))

        val result = LocalMediaPolicy.filesToDelete(files, setOf("a.jpg", "b.jpg"), now, maxBytes = 1_000)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `over the cap the oldest waiting files go first`() {
        val files = listOf(
            file("newest.jpg", 400, old + 200),
            file("oldest.jpg", 400, old),
            file("middle.jpg", 400, old + 100)
        )
        val waiting = setOf("newest.jpg", "oldest.jpg", "middle.jpg")

        val result = LocalMediaPolicy.filesToDelete(files, waiting, now, maxBytes = 1_000)

        assertEquals(listOf("oldest.jpg"), result)
    }
}
