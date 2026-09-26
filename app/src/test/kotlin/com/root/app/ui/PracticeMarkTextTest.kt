package com.root.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class PracticeMarkTextTest {
    private val now = TimeUnit.DAYS.toMillis(10)

    @Test fun futureTimestampsClampToJustNow() {
        assertEquals("Last practiced just now.", lastPracticedCaption(now + 1_000, now))
    }

    @Test fun underAMinuteReadsAsJustNow() {
        assertEquals("Last practiced just now.", lastPracticedCaption(now - 59_000, now))
    }

    @Test fun minutesPluralizeCorrectly() {
        assertEquals("Last practiced 1 minute ago.", lastPracticedCaption(now - TimeUnit.MINUTES.toMillis(1), now))
        assertEquals("Last practiced 12 minutes ago.", lastPracticedCaption(now - TimeUnit.MINUTES.toMillis(12), now))
    }

    @Test fun hoursPluralizeCorrectly() {
        assertEquals("Last practiced 1 hour ago.", lastPracticedCaption(now - TimeUnit.HOURS.toMillis(1), now))
        assertEquals("Last practiced 5 hours ago.", lastPracticedCaption(now - TimeUnit.HOURS.toMillis(5), now))
    }

    @Test fun daysSwitchToYesterdayThenDayCount() {
        assertEquals("Last practiced yesterday.", lastPracticedCaption(now - TimeUnit.DAYS.toMillis(1), now))
        assertEquals("Last practiced 6 days ago.", lastPracticedCaption(now - TimeUnit.DAYS.toMillis(6), now))
    }
}

