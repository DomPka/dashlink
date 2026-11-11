package com.dompka.dashlink

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The download queue and the copy of it that survives the process being killed. */
class JobsTest {
    private val saved = ArrayList<String>()

    private fun clip(name: String, size: Long = 100) = Cam.Clip("/SD/Normal/F/$name", size, "2026-01-01 00:00:00", "1920x1080", "60")

    @Before
    fun setUp() {
        reset()
        Jobs.saver = { saved.add(it) }
    }

    @After
    fun reset() {
        Jobs.all.clear()
        Jobs.paused = null
        Jobs.pausedByLink = false
        Jobs.interrupted = false
        Jobs.saver = null
        saved.clear()
    }

    @Test
    fun add_skipsClipsAlreadyOnTheirWay() {
        assertEquals(2, Jobs.add(listOf(clip("A.MP4"), clip("B.MP4"))))
        assertEquals(1, Jobs.add(listOf(clip("A.MP4"), clip("B.MP4"), clip("C.MP4"))))
        assertEquals(0, Jobs.add(listOf(clip("C.MP4"), clip("C.MP4"))))
        assertEquals(listOf("A.MP4", "B.MP4", "C.MP4"), Jobs.all.map { it.clip.name })
        // Downloading, waiting and saving count as "on its way" too.
        Jobs.all[0].state = Jobs.DOWNLOADING
        Jobs.all[1].state = Jobs.WAITING
        Jobs.all[2].state = Jobs.SAVING
        assertEquals(0, Jobs.add(listOf(clip("A.MP4"), clip("B.MP4"), clip("C.MP4"))))
        // A finished, failed or stopped clip can be asked for again.
        Jobs.all[0].state = Jobs.SAVED
        Jobs.all[1].state = Jobs.FAILED
        Jobs.all[2].state = Jobs.CANCELLED
        assertEquals(3, Jobs.add(listOf(clip("A.MP4"), clip("B.MP4"), clip("C.MP4"))))
        assertEquals(6, Jobs.all.size)
        // So can one that is still being stopped: the new job does not inherit the old one's fate.
        Jobs.all[3].cancel = true
        assertEquals(1, Jobs.add(listOf(clip("A.MP4"))))
        assertFalse(Jobs.all.last().cancel)
    }

    @Test
    fun next_isTheFirstQueuedJobThatWasNotStopped() {
        assertNull(Jobs.next())
        assertFalse(Jobs.hasWork())
        Jobs.add(listOf(clip("A.MP4"), clip("B.MP4"), clip("C.MP4")))
        Jobs.all[0].state = Jobs.SAVED
        Jobs.all[1].cancel = true
        assertEquals("C.MP4", Jobs.next()!!.clip.name)
        assertTrue(Jobs.hasWork())
    }

    @Test
    fun cancelAll_marksEverythingUnfinished() {
        Jobs.add(listOf(clip("A.MP4"), clip("B.MP4"), clip("C.MP4")))
        Jobs.all[0].state = Jobs.SAVED
        Jobs.all[1].state = Jobs.DOWNLOADING
        Jobs.all[1].dest = "content://media/1"
        Jobs.all[2].dest = "content://media/2"
        Jobs.paused = "camera gone"
        Jobs.pausedByLink = true
        Jobs.interrupted = true
        assertEquals(setOf("content://media/1", "content://media/2"), Jobs.dests())

        Jobs.cancelAll()
        assertEquals(listOf(false, true, true), Jobs.all.map { it.cancel })
        assertNull(Jobs.next())
        assertNull(Jobs.paused)
        assertFalse(Jobs.pausedByLink)
        assertFalse(Jobs.interrupted)
        assertTrue("nothing is worth keeping any more: every unfinished file may go", Jobs.dests().isEmpty())
        assertEquals("the saved queue is now empty", "", saved.last())
    }

    @Test
    fun savedCopy_roundTrip() {
        val awkward = Cam.Clip("/SD/Normal/F/tab\there\nnew\\line\r.MP4", 1234567890123L, "2026-01-01\t00:00", "", "")
        Jobs.add(listOf(clip("A.MP4", 42), awkward, clip("C.MP4", -1)))
        Jobs.all[0].dest = "content://media/external_primary/video/media/17"
        val text = Jobs.encode()
        assertEquals("one line per job", 3, text.split('\n').size)

        val back = Jobs.decode(text)
        assertEquals(3, back.size)
        assertEquals("/SD/Normal/F/A.MP4", back[0].clip.path)
        assertEquals(42L, back[0].clip.size)
        assertEquals("2026-01-01 00:00:00", back[0].clip.time)
        assertEquals("1920x1080", back[0].clip.res)
        assertEquals("60", back[0].clip.secs)
        assertEquals("content://media/external_primary/video/media/17", back[0].dest)
        assertEquals(Jobs.QUEUED, back[0].state)
        assertEquals(awkward.path, back[1].clip.path)
        assertEquals(awkward.size, back[1].clip.size)
        assertEquals(awkward.time, back[1].clip.time)
        assertNull(back[1].dest)
        assertEquals(-1L, back[2].clip.size)
    }

    @Test
    fun savedCopy_onlyHoldsWhatIsStillWanted() {
        Jobs.add(listOf(clip("A.MP4"), clip("B.MP4"), clip("C.MP4"), clip("D.MP4"), clip("E.MP4")))
        Jobs.all[0].state = Jobs.SAVED
        Jobs.all[1].state = Jobs.FAILED
        Jobs.all[2].state = Jobs.DOWNLOADING       // was in progress when the process died: still wanted
        Jobs.all[3].cancel = true
        assertEquals(listOf("C.MP4", "E.MP4"), Jobs.decode(Jobs.encode()).map { it.clip.name })
    }

    @Test
    fun savedCopy_damagedTextIsSkippedNotFatal() {
        assertTrue(Jobs.decode(null).isEmpty())
        assertTrue(Jobs.decode("").isEmpty())
        val good = "/SD/Normal/F/A.MP4\t5\tt\tr\ts\t"
        val text = listOf(
            "garbage",
            "\t\t\t\t\t",
            "/SD/x.MP4\tnot-a-number\tt\tr\ts\t",
            "/SD/folder/\t5\tt\tr\ts\t",
            "too\tfew",
            good,
            good,                                     // the same clip twice
            "/SD/Normal/F/B.MP4\t7\tt\tr\ts\tcontent://x\textra\tfields",
            "trailing backslash\\"
        ).joinToString("\n")
        val back = Jobs.decode(text)
        assertEquals(listOf("/SD/Normal/F/A.MP4", "/SD/Normal/F/B.MP4"), back.map { it.clip.path })
        assertEquals("content://x", back[1].dest)
    }

    @Test
    fun everyChangeIsWritten_andAFailingWriteIsNotFatal() {
        Jobs.add(listOf(clip("A.MP4")))
        assertEquals(1, saved.size)
        assertTrue(saved[0].startsWith("/SD/Normal/F/A.MP4\t100\t"))
        Jobs.cancelAll()
        assertEquals(2, saved.size)

        Jobs.saver = { throw IllegalStateException("disk full") }
        assertEquals(1, Jobs.add(listOf(clip("B.MP4"))))
        Jobs.save()
        Jobs.saver = null
        Jobs.save()
    }

    @Test
    fun restore_bringsBackTheQueueOfAKilledProcess() {
        Jobs.add(listOf(clip("A.MP4"), clip("B.MP4")))
        Jobs.all[0].state = Jobs.DOWNLOADING
        Jobs.all[0].done = 77
        Jobs.all[0].dest = "content://media/9"
        val text = saved.also { Jobs.save() }.last()

        // The process dies; a new one starts with nothing in memory.
        Jobs.all.clear()
        Jobs.restore(text, wasWorking = true)
        assertEquals(listOf("A.MP4", "B.MP4"), Jobs.all.map { it.clip.name })
        assertTrue(Jobs.all.all { it.state == Jobs.QUEUED && !it.cancel })
        assertEquals("content://media/9", Jobs.all[0].dest)
        assertTrue(Jobs.interrupted)
        assertTrue(Jobs.hasWork())

        // Restoring twice does not double the queue.
        Jobs.restore(text, wasWorking = true)
        assertEquals(2, Jobs.all.size)
    }

    @Test
    fun restore_isOnlyAnInterruptionIfWorkWasCutOff() {
        Jobs.restore("/SD/Normal/F/A.MP4\t5\tt\tr\ts\t", wasWorking = false)
        assertEquals(1, Jobs.all.size)
        assertFalse("the queue was merely paused, nothing was killed", Jobs.interrupted)
        Jobs.all.clear()
        Jobs.restore("", wasWorking = true)
        assertFalse("nothing was left to do", Jobs.interrupted)
        Jobs.restore(null, wasWorking = true)
        assertTrue(Jobs.all.isEmpty())
    }

    @Test
    fun finishedJobsDoNotPileUpForEver() {
        Jobs.add((0 until 1000).map { clip("F$it.MP4") })
        for (j in Jobs.all) j.state = Jobs.SAVED
        Jobs.add(listOf(clip("LAST.MP4")))
        assertTrue("old finished jobs are dropped: ${Jobs.all.size}", Jobs.all.size <= 301)
        assertEquals("LAST.MP4", Jobs.all.last().clip.name)
        assertEquals("F999.MP4", Jobs.all[Jobs.all.size - 2].clip.name)
        // Unfinished ones are never dropped, however many.
        reset()
        Jobs.add((0 until 1000).map { clip("Q$it.MP4") })
        assertEquals(1000, Jobs.all.size)
    }
}
