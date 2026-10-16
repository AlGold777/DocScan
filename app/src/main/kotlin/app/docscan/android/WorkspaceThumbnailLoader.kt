package app.docscan.android

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import app.docscan.core.model.ImageVersionId
import app.docscan.core.model.PageId
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** A sampled, byte-bounded thumbnail cache shared by workspace views in one Activity. */
internal class WorkspaceThumbnailLoader(
    private val worker: Executor,
    private val mainPoster: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
    val maxCacheBytes: Long = DEFAULT_CACHE_BYTES,
    private val decode: (File, Int, Int) -> Bitmap? = { file, width, height ->
        ProductImageDecoder.decode(
            file = file,
            maxPixels = width.toLong() * height,
            maxWidth = width,
            maxHeight = height,
        )
    },
) {
    internal data class Key(
        val pageId: PageId,
        val imageVersionId: ImageVersionId,
        val sourceSizeBytes: Long,
        val targetWidthPx: Int,
        val targetHeightPx: Int,
    )

    private data class Binding(val key: Key, val file: File, val token: Long)
    private class Job(val cancelled: AtomicBoolean = AtomicBoolean(false))
    private val cache = LinkedHashMap<Key, Bitmap>(16, 0.75f, true)
    private val bindings = IdentityHashMap<ImageView, Binding>()
    private val inFlight = mutableMapOf<Key, Job>()
    private val failedKeys = mutableSetOf<Key>()
    private val workSlots = Semaphore(MAX_PENDING)
    private var nextToken = 1L
    private var closed = false
    private var cacheBytesValue = 0L
    private var decodeCountValue = 0

    val cachedBytes: Long get() = cacheBytesValue
    val pendingCount: Int get() = inFlight.values.count { !it.cancelled.get() }
    val waitingCount: Int get() = waitingBindings().size
    val decodeCount: Int get() = decodeCountValue
    val boundTargetCount: Int get() = bindings.size

    /** Bind or replace the target's current request. Call on the main thread. */
    fun bind(target: ImageView, key: Key, file: File) {
        if (bindings[target]?.key == key) return
        unbind(target)
        if (closed) return
        val binding = Binding(key, file, nextToken++)
        bindings[target] = binding
        target.setImageDrawable(ColorDrawable(PLACEHOLDER_COLOR))
        cache[key]?.let { bitmap ->
            target.setImageDrawable(BitmapDrawable(target.resources, bitmap))
            return
        }
        scheduleWaitingRequests()
    }

    /** Promote live bound targets only; the binding map is the bounded waiter set. */
    private fun scheduleWaitingRequests() {
        if (closed) return
        waitingBindings().forEach { binding ->
            if (!workSlots.tryAcquire()) return
            val stillBound = bindings.values.any { it.key == binding.key }
            if (!stillBound || cache.containsKey(binding.key) || inFlight.containsKey(binding.key) || binding.key in failedKeys) {
                workSlots.release()
                return@forEach
            }
            startJob(binding)
        }
    }

    private fun waitingBindings(): List<Binding> = bindings.values
        .sortedBy { it.token }
        .distinctBy { it.key }
        .filter { it.key !in cache && it.key !in inFlight && it.key !in failedKeys }

    private fun startJob(binding: Binding) {
        val key = binding.key
        val job = Job()
        inFlight[key] = job
        try {
            worker.execute {
                val bitmap = if (job.cancelled.get()) null else try {
                    decode(binding.file, key.targetWidthPx, key.targetHeightPx)
                } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
                try {
                    mainPoster {
                        try {
                            if (inFlight[key] === job) inFlight.remove(key)
                            if (closed || job.cancelled.get()) {
                                bitmap?.recycleIfSafe()
                                return@mainPoster
                            }
                            val validTargets = bindings.entries
                                .filter { it.value.key == key }
                                .map { it.key to it.value }
                            if (validTargets.isEmpty()) {
                                bitmap?.recycleIfSafe()
                                return@mainPoster
                            }
                            if (bitmap == null) {
                                // Do not retry a failed decode in a promotion loop. This marker
                                // lives only as long as a target for the key remains bound.
                                failedKeys.add(key)
                                return@mainPoster
                            }
                            decodeCountValue += 1
                            val bytes = bitmap.allocationByteCount.toLong()
                            if (bytes <= maxCacheBytes) {
                                cache[key] = bitmap
                                cacheBytesValue += bytes
                                trimCache()
                            }
                            validTargets.forEach { (view, expected) ->
                                if (bindings[view] == expected) {
                                    view.setImageDrawable(BitmapDrawable(view.resources, bitmap))
                                }
                            }
                        } finally {
                            workSlots.release()
                            scheduleWaitingRequests()
                        }
                    }
                } catch (_: RuntimeException) {
                    bitmap?.recycleIfSafe()
                    workSlots.release()
                }
            }
        } catch (_: RuntimeException) {
            if (inFlight[key] === job) inFlight.remove(key)
            job.cancelled.set(true)
            failedKeys.add(key)
            workSlots.release()
        }
    }

    /** Clear a target's image and invalidate any result still in flight for it. */
    fun unbind(target: ImageView) {
        val old = bindings.remove(target)
        target.setImageDrawable(null)
        old?.let(::cancelIfUnused)
    }

    /** Drop retained cache references and bindings while keeping the loader usable. */
    fun clear() {
        bindings.keys.toList().forEach { it.setImageDrawable(null) }
        bindings.clear()
        inFlight.values.forEach { it.cancelled.set(true) }
        failedKeys.clear()
        cache.values.forEach { it.recycleIfSafe() }
        cache.clear()
        cacheBytesValue = 0L
    }

    /** Terminal lifecycle release; late worker results are discarded and recycled. */
    fun close() {
        if (closed) return
        clear()
        closed = true
    }

    private fun trimCache() {
        val iterator = cache.entries.iterator()
        while (cacheBytesValue > maxCacheBytes && iterator.hasNext()) {
            val entry = iterator.next()
            cacheBytesValue -= entry.value.allocationByteCount.toLong()
            iterator.remove()
            // Bound views keep their own reference until recycled/rebound; never recycle a shared bitmap here.
        }
    }

    private fun cancelIfUnused(binding: Binding) {
        if (bindings.values.any { it.key == binding.key }) return
        inFlight[binding.key]?.cancelled?.set(true)
        failedKeys.remove(binding.key)
    }

    private fun Bitmap.recycleIfSafe() {
        if (!isRecycled) recycle()
    }

    companion object {
        const val DEFAULT_CACHE_BYTES = 8L * 1024L * 1024L
        private const val MAX_PENDING = 16
        private const val PLACEHOLDER_COLOR = 0xff424242.toInt()
    }
}
