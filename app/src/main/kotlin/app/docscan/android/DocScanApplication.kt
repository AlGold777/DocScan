package app.docscan.android

import android.app.Application
import app.docscan.core.model.PageId
import app.docscan.core.ocr.OcrTaskQueue
import app.docscan.core.schedule.HeavyWorkScheduler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DocScanApplication : Application() {
    val heavyWorkScheduler = HeavyWorkScheduler()
    private val ocrExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val defaultOcrAdapter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { TesseractOcrAdapter(this) }
    internal var ocrAdapterOverrideForTest: TesseractOcrAdapter? = null
    private val ocrAdapter: TesseractOcrAdapter
        get() = ocrAdapterOverrideForTest ?: defaultOcrAdapter

    private val ocrQueue = OcrTaskQueue<PageId>(
        executor = ocrExecutor,
        stopActiveTask = { ocrAdapter.cancelCurrentRecognition() },
    )

    fun enqueueOcr(
        pageId: PageId,
        replaceExisting: Boolean = false,
        requestKey: String? = null,
        task: (TesseractOcrAdapter, () -> Boolean) -> Unit,
    ) {
        ocrQueue.enqueue(pageId, replaceExisting, requestKey) { isCancelled ->
            if (!isCancelled()) task(ocrAdapter, isCancelled)
        }
    }

    fun cancelOcrForPages(pageIds: Collection<PageId>) {
        ocrQueue.cancel(pageIds)
    }

    fun awaitOcrForPages(pageIds: Collection<PageId>, timeoutMs: Long = 60_000L) {
        ocrQueue.await(pageIds, timeoutMs)
    }

    private val defaultProductStorage: ProductStorage by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProductStorage(this)
    }

    internal var productStorageOverrideForTest: ProductStorage? = null
    internal var productStorageProviderForTest: (() -> ProductStorage)? = null
    val productStorage: ProductStorage
        get() = productStorageProviderForTest?.invoke() ?: productStorageOverrideForTest ?: defaultProductStorage
}
