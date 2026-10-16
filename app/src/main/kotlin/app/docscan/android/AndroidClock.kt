package app.docscan.android

import android.os.SystemClock
import app.docscan.core.time.Clock
import app.docscan.core.time.TimeReading
import java.util.UUID

/** Wall time is for display/retention; elapsed realtime is valid only within this process epoch. */
class AndroidClock : Clock {
    private val epochToken = UUID.randomUUID().toString()

    override fun read(): TimeReading = TimeReading(
        wallTimeMillis = System.currentTimeMillis().coerceAtLeast(0L),
        monotonicTimeNanos = SystemClock.elapsedRealtimeNanos().coerceAtLeast(0L),
        monotonicEpochToken = epochToken,
    )
}
