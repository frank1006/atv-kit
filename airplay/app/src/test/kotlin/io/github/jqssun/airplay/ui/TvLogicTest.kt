package io.github.jqssun.airplay.ui

import io.github.jqssun.airplay.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLogicTest {

    @Test fun formatTime_minutesAndSeconds() {
        assertEquals("0:00", formatTime(0))
        assertEquals("0:09", formatTime(9_999))
        assertEquals("3:54", formatTime(234_000))
        assertEquals("59:59", formatTime(3_599_000))
    }

    @Test fun formatTime_hoursFromOneHourUp() {
        assertEquals("1:00:00", formatTime(3_600_000))
        assertEquals("1:00:53", formatTime(3_653_000))
    }

    @Test fun formatTime_negativeReadsAsZero() {
        assertEquals("0:00", formatTime(-5_000))
    }

    @Test fun longForm_boundaryIsTwentyMinutes() {
        assertFalse(isLongForm(LONG_FORM_MS))
        assertTrue(isLongForm(LONG_FORM_MS + 1))
        assertFalse(isLongForm(3 * 60_000))   // a song
        assertTrue(isLongForm(40 * 60_000))   // a podcast episode
        assertFalse(isLongForm(-1))           // unknown duration
    }

    @Test fun progress_clampsAndHandlesUnknownDuration() {
        assertEquals(0.5f, progressFraction(30_000, 60_000), 0f)
        assertEquals(1f, progressFraction(90_000, 60_000), 0f)
        assertEquals(0f, progressFraction(-1, 60_000), 0f)
        assertEquals(0f, progressFraction(30_000, 0), 0f)
    }

    @Test fun nextOption_wrapsAndRecoversFromUnknown() {
        val options = listOf("auto", "on", "off")
        assertEquals("on", nextOption(options, "auto"))
        assertEquals("auto", nextOption(options, "off"))
        assertEquals("auto", nextOption(options, "1920x1080"))
    }

    @Test fun parseFor_acceptsOnlyWhatThePrefAccepts() {
        assertEquals(8080, parseFor(Prefs.SERVER_PORT, " 8080 "))
        assertNull(parseFor(Prefs.SERVER_PORT, "70000"))
        assertNull(parseFor(Prefs.SERVER_PORT, "abc"))
        assertEquals("1280x720", parseFor(Prefs.RESOLUTION, "1280x720"))
        assertNull(parseFor(Prefs.RESOLUTION, "1280 by 720"))
        assertNull(parseFor(Prefs.MAX_FPS, "0"))
    }

    @Test fun playRequest_staysThroughAStaleReport() {
        // the sequence seen on a Mi Box with an iPhone podcast: paused at 0, optimistic "paused",
        // a stale "playing" from the sender's progress update, then the real "paused" 7 s later
        val r = PlayRequest(playing = false, at = 0)
        assertFalse(r.settled(reported = false, now = 10))
        assertFalse(r.settled(reported = true, now = 230))
        assertFalse(r.settled(reported = true, now = 6_000))
        assertFalse(r.settled(reported = false, now = 7_200))
        assertFalse(r.settled(reported = false, now = 9_100))
        assertTrue(r.settled(reported = false, now = 9_200))
    }

    @Test fun playRequest_dropsSoonOnAWellBehavedSender() {
        val r = PlayRequest(playing = true, at = 0)
        assertFalse(r.settled(reported = true, now = 300))
        assertTrue(r.settled(reported = true, now = 2_300))
    }

    @Test fun playRequest_givesUpWhenTheSenderNeverAgrees() {
        val r = PlayRequest(playing = false, at = 0)
        assertFalse(r.settled(reported = true, now = 14_999))
        assertTrue(r.settled(reported = true, now = 15_000))
    }
}
