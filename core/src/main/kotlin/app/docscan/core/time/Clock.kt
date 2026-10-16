package app.docscan.core.time

import java.util.UUID

data class TimeReading(
    val wallTimeMillis: Long,
    val monotonicTimeNanos: Long,
    val monotonicEpochToken: String,
) {
    init {
        require(wallTimeMillis >= 0L)
        require(monotonicTimeNanos >= 0L)
        require(monotonicEpochToken.isNotBlank())
    }
}

fun interface Clock {
    fun read(): TimeReading
}

class SystemClock : Clock {
    private val epochToken = UUID.randomUUID().toString()

    override fun read(): TimeReading = TimeReading(
        wallTimeMillis = System.currentTimeMillis(),
        monotonicTimeNanos = System.nanoTime().coerceAtLeast(0L),
        monotonicEpochToken = epochToken,
    )
}

class FakeClock(initial: TimeReading) : Clock {
    private var current = initial

    @Synchronized
    override fun read(): TimeReading = current

    @Synchronized
    fun set(reading: TimeReading) {
        current = reading
    }
}
