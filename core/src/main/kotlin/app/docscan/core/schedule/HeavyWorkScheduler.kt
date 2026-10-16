package app.docscan.core.schedule

/** Runtime admission for non-interactive image work. Capture must not use this gate. */
class HeavyWorkScheduler(
    private val maxConcurrentJobs: Int = DEFAULT_MAX_CONCURRENT_JOBS,
    private val decodedByteBudget: Long = DEFAULT_DECODED_BYTE_BUDGET,
) {
    private val monitor = Object()
    private var runningJobs = 0
    private var reservedBytes = 0L

    init {
        require(maxConcurrentJobs > 0)
        require(decodedByteBudget > 0L)
    }

    fun <T> withAdmission(decodedBytes: Long, work: () -> T): T {
        require(decodedBytes in 1..decodedByteBudget) {
            "Heavy job estimate $decodedBytes exceeds the decoded-image budget $decodedByteBudget"
        }
        synchronized(monitor) {
            while (runningJobs >= maxConcurrentJobs || reservedBytes > decodedByteBudget - decodedBytes) {
                monitor.wait()
            }
            runningJobs += 1
            reservedBytes += decodedBytes
        }
        try {
            return work()
        } finally {
            synchronized(monitor) {
                runningJobs -= 1
                reservedBytes -= decodedBytes
                monitor.notifyAll()
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT_JOBS = 1
        const val DEFAULT_DECODED_BYTE_BUDGET = 96L * 1024L * 1024L
    }
}
