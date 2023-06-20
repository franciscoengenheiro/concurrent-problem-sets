package pt.isel.pc.problemsets.async

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * A suspendable version of
 * [CountDownLatch](https://docs.oracle.com/javase/7/docs/api/java/util/concurrent/CountDownLatch.html).
 * @param initialCount the initial counter value.
 */
class SuspendableCountDownLatch(
    initialCount: Int
) {
    private var counter = initialCount
    private val continuationList = mutableListOf<Continuation<Unit>>()
    private val lock = ReentrantLock()

    /**
     * Decrements the counter and resumes all suspended callers when the counter reaches zero.
     */
    fun countDown() {
        var listToResume: List<Continuation<Unit>>? = null
        lock.withLock {
            if (counter == 0) {
                return@withLock
            }
            counter -= 1
            if (counter == 0) {
                listToResume = continuationList.toList()
            }
        }
        listToResume?.forEach {
            it.resume(Unit)
        }
    }

    /**
     * Suspends the caller until the counter reaches zero.
     */
    suspend fun await() {
        lock.lock()
        if (counter == 0) {
            lock.unlock()
            return
        }
        suspendCoroutine<Unit> { continuation ->
            continuationList.add(continuation)
            lock.unlock()
        }
    }
}