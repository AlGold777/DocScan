package app.docscan.core.ocr

import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Keeps at most one current OCR request per key and lets a newer request replace it. */
class OcrTaskQueue<K : Any>(
    private val executor: ExecutorService,
    private val stopActiveTask: (K) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private class Job(val requestKey: String?) {
        val cancelled = AtomicBoolean(false)
        @Volatile var future: Future<*>? = null
    }

    private val jobs = ConcurrentHashMap<K, Job>()
    private val active = AtomicReference<Pair<K, Job>?>(null)
    private val lock = Any()

    /**
     * Returns true when a task was submitted. An equal non-null request key is deduplicated while
     * its request is pending or running, unless [replaceExisting] is true.
     */
    fun enqueue(
        key: K,
        replaceExisting: Boolean = false,
        requestKey: String? = null,
        task: (isCancelled: () -> Boolean) -> Unit,
    ): Boolean = synchronized(lock) {
        val existing = jobs[key]
        if (existing != null && !replaceExisting && !existing.cancelled.get()) {
            if (requestKey != null && existing.requestKey == requestKey && existing.future?.isDone != true) return@synchronized false
            if (requestKey == null && existing.future?.isDone != true) return@synchronized false
        }

        // Stop/cancel the old request before submitting its replacement. With the single-thread
        // executor this ensures the native stop hook cannot catch the replacement's recognition.
        if (existing != null) cancelLocked(key, existing)
        val job = Job(requestKey)
        jobs[key] = job
        try {
            job.future = executor.submit {
                val activeEntry = key to job
                val started = synchronized(lock) {
                    if (job.cancelled.get()) false else {
                        active.set(activeEntry)
                        true
                    }
                }
                try {
                    if (started && !job.cancelled.get()) task { job.cancelled.get() }
                } finally {
                    // Serialize active-job retirement with cancelLocked so stopActiveTask cannot
                    // observe one job and accidentally stop the next recognition.
                    synchronized(lock) { active.compareAndSet(activeEntry, null) }
                }
            }
        } catch (failure: Throwable) {
            jobs.remove(key, job)
            throw failure
        }
        true
    }

    fun cancel(keys: Collection<K>) {
        keys.forEach { key ->
            synchronized(lock) { jobs[key]?.let { cancelLocked(key, it) } }
        }
    }

    fun await(keys: Collection<K>, timeoutMs: Long = 60_000L) {
        require(timeoutMs >= 0) { "timeoutMs must be non-negative" }
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val startedAt = nanoTime()

        for (key in keys) {
            while (true) {
                val job = synchronized(lock) { jobs[key] } ?: break
                val future = job.future ?: continue
                try {
                    val elapsed = nanoTime() - startedAt
                    val remaining = if (elapsed < 0L || timeoutNanos < elapsed) 0L else timeoutNanos - elapsed
                    if (remaining <= 0L && !future.isDone) throw TimeoutException("Timed out waiting for OCR page $key")
                    future.get(maxOf(0L, remaining), TimeUnit.NANOSECONDS)
                } catch (error: ExecutionException) {
                    if (isCurrent(key, job)) throw (error.cause ?: error)
                } catch (error: CancellationException) {
                    if (isCurrent(key, job)) throw error
                } catch (error: TimeoutException) {
                    if (isCurrent(key, job)) throw error
                }

                if (isCurrent(key, job)) {
                    if (job.cancelled.get()) throw CancellationException("OCR page $key was cancelled")
                    break
                }
            }
        }
    }

    private fun isCurrent(key: K, job: Job): Boolean = synchronized(lock) { jobs[key] === job }

    private fun cancelLocked(key: K, job: Job) {
        job.cancelled.set(true)
        val activeEntry = active.get()
        if (activeEntry?.first == key && activeEntry.second === job) {
            stopActiveTask(key)
        } else {
            job.future?.cancel(false)
        }
    }
}
