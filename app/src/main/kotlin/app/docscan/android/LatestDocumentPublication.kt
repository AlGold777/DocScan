package app.docscan.android

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One queued UI task and one replaceable value, even when the main looper is blocked. */
class LatestDocumentPublication<T:Any>(
    private val post:(Runnable)->Unit,
    private val remove:(Runnable)->Unit,
    private val apply:(T)->Unit,
) : AutoCloseable {
    private val latest=AtomicReference<T?>()
    private val scheduled=AtomicBoolean()
    private val closed=AtomicBoolean()
    private val drain=object:Runnable {
        override fun run() {
            try {
                val value=latest.getAndSet(null)
                if(value!=null && !closed.get()) apply(value)
            } finally {
                scheduled.set(false)
                if(latest.get()!=null && !closed.get()) schedule()
            }
        }
    }
    fun offer(value:T) {
        if(closed.get()) return
        latest.set(value)
        if(closed.get()) { latest.set(null); return }
        schedule()
    }
    private fun schedule() {
        if(!closed.get() && scheduled.compareAndSet(false,true)) {
            post(drain)
            if(closed.get()) remove(drain)
        }
    }
    /** Invalidate pending values without disabling a resumed camera session. */
    fun clear() { latest.set(null) }
    override fun close() {
        closed.set(true); latest.set(null); remove(drain); scheduled.set(false)
    }
}
