package io.github.markusnuo.routelog.upload

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one thread all upload work runs on, whether it comes from a job or from the app: uploads,
 * expiry, and the maintenance at app start. One after the other, so none of it runs in parallel.
 */
object UploadWorker {
    private const val TAG = "UploadWorker"
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "routelog-upload") }
    private val pending = AtomicInteger()

    /** Whether upload work is queued or running. A backup must not copy the database meanwhile. */
    val isBusy: Boolean get() = pending.get() > 0

    fun execute(work: () -> Unit) {
        pending.incrementAndGet()
        try {
            executor.execute {
                try {
                    work()
                } catch (e: RuntimeException) {
                    // An uncaught exception on this thread would crash the app, recording included.
                    Log.e(TAG, "Upload work failed: ${e.javaClass.simpleName}")
                } finally {
                    pending.decrementAndGet()
                }
            }
        } catch (e: RuntimeException) {
            pending.decrementAndGet()
            throw e
        }
    }
}
