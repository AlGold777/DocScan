package app.docscan.android

import android.app.Application
import app.docscan.core.model.PageId
import app.docscan.core.schedule.HeavyWorkScheduler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

class DocScanApplication : Application() {
    val heavyWorkScheduler = HeavyWorkScheduler()
    private val ocrExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val defaultOcrAdapter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { TesseractOcrAdapter(this) }
    internal var ocrAdapterOverrideForTest: TesseractOcrAdapter? = null
    private val ocrAdapter: TesseractOcrAdapter
        get() = ocrAdapterOverrideForTest ?: defaultOcrAdapter

    private class OcrJob(val cancelled: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false)) {
        @Volatile var future: Future<*>? = null
    }

    private val ocrJobs = ConcurrentHashMap<PageId, OcrJob>()
    private val activeOcrJob = AtomicReference<Pair<PageId, OcrJob>?>(null)

    fun enqueueOcr(pageId: PageId, task: (TesseractOcrAdapter, () -> Boolean) -> Unit) {
        val job = OcrJob()
        if (ocrJobs.putIfAbsent(pageId, job) != null) return
        val future = ocrExecutor.submit {
            val active = pageId to job
            try {
                if (job.cancelled.get()) return@submit
                activeOcrJob.set(active)
                if (job.cancelled.get()) return@submit
                if (!job.cancelled.get()) task(ocrAdapter) { job.cancelled.get() }
            } finally {
                activeOcrJob.compareAndSet(active, null)
                ocrJobs.remove(pageId, job)
            }
        }
        job.future = future
        if (job.cancelled.get()) future.cancel(false)
    }

    fun cancelOcrForPages(pageIds: Collection<PageId>) {
        pageIds.forEach { pageId ->
            val job = ocrJobs[pageId] ?: return@forEach
            job.cancelled.set(true)
            if (activeOcrJob.get() == (pageId to job)) {
                ocrAdapter.cancelCurrentRecognition()
            } else {
                if (job.future?.cancel(false) == true) ocrJobs.remove(pageId, job)
            }
        }
    }

    private val defaultProductStorage: ProductStorage by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProductStorage(this)
    }

    internal var productStorageOverrideForTest: ProductStorage? = null
    internal var productStorageProviderForTest: (() -> ProductStorage)? = null
    val productStorage: ProductStorage
        get() = productStorageProviderForTest?.invoke() ?: productStorageOverrideForTest ?: defaultProductStorage
}
