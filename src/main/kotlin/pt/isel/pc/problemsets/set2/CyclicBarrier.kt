package pt.isel.pc.problemsets.set2

import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * A mechanism used to synchronize a group of threads to wait for each other to reach a specific
 * point before proceeding further.
 * The barrier is *cyclic* in the sense that it can be reused again and again.
 * @param parties the number of threads that will be synchronized by this barrier.
 * @param barrierAction the [Runnable] to execute when the last thread arrives at the barrier.
 * @throws IllegalArgumentException if [parties] is less than 1. Once created, it cannot be changed.
 */
class CyclicBarrier(private val parties: Int, private val barrierAction: Runnable?) {
    constructor(parties: Int) : this(parties, null)

    init {
        require(parties > 1) { "The number of parties must be greater than 1" }
    }

    private val lock = ReentrantLock()
    private val barrierCondition: Condition = lock.newCondition()

    // Internal state
    private var barrierRequest = BarrierRequest()

    // Represents a request to form a barrier
    private class BarrierRequest(
        var nOfThreadsWaiting: Int = 0,
        var wasBroken: Boolean = false,
        var wasCompleted: Boolean = false
    )

    /**
     * Waits until all parties have invoked await on this barrier.
     * @return the arrival index of the current thread, where index [parties] - 1 indicates the first to arrive
     * and zero indicates the last to arrive.
     * @throws InterruptedException if the current thread was interrupted while waiting, and the barrier has not broken.
     * @throws BrokenBarrierException if another thread was interrupted or timed out while the current thread was
     * waiting, or the barrier was resetted, or the barrier was broken when await was called.
     */
    @Throws(InterruptedException::class, BrokenBarrierException::class)
    fun await(): Int = await(Duration.INFINITE)

    /**
     * Waits until all parties have invoked await on this barrier for up to the specified [timeout] time.
     * @param timeout the maximum time that this thread is willing to wait for the barrier to be triggered.
     * @return the arrival index of the current thread, where index [parties] - 1 indicates the first to arrive
     * and zero indicates the last to arrive.
     * @throws InterruptedException if the current thread was interrupted while waiting, and the barrier has not broken.
     * @throws TimeoutException if the specified timeout elapses and the barrier has not broken.
     * @throws BrokenBarrierException if another thread was interrupted or timed out while the current thread was
     * waiting, or the barrier was resetted, or the barrier was broken when await was called.
     */
    @Throws(InterruptedException::class, TimeoutException::class, BrokenBarrierException::class)
    fun await(timeout: Duration): Int {
        lock.withLock {
            // fast-path(1) - the barrier was broken
            if (barrierRequest.wasBroken)
                throw BrokenBarrierException()
            val indexOfArrival = parties - (barrierRequest.nOfThreadsWaiting + 1)
            // fast-path(2) - the thread that enters is the last thread to arrive at an unbroken barrier
            if (indexOfArrival == 0) {
                barrierRequest.wasCompleted = true
                executeBarrierAction()
                barrierCondition.signalAll()
                barrierRequest = BarrierRequest()
                return indexOfArrival // 0
            }
            // The thread does not want to wait for the barrier to be broken
            if (timeout == Duration.ZERO) {
                breakBarrier()
                // give-up immediately
                throw TimeoutException()
            }
            // wait-path
            var remainingNanos: Long = timeout.inWholeNanoseconds
            val localBarrierRequest = barrierRequest
            // another thread joins the await condition
            barrierRequest.nOfThreadsWaiting++
            while (true) {
                try {
                    remainingNanos = barrierCondition.awaitNanos(remainingNanos)
                } catch (ex: InterruptedException) {
                    if (localBarrierRequest.wasBroken) {
                        throw BrokenBarrierException()
                    } else if (localBarrierRequest.wasCompleted) {
                        // the barrier was not broken but completed by another thread,
                        // so the thread must return successfully while keeping the interrupt request alive
                        Thread.currentThread().interrupt()
                        return indexOfArrival // (1..parties-1)
                    } else {
                        // the barrier was not broken nor completed, so, since this thread was interrupted,
                        // the barrier must be broken by this thread
                        breakBarrier()
                        throw InterruptedException()
                    }
                }
                // Check if the barrier was broken by another thread
                if (localBarrierRequest.wasBroken) {
                    throw BrokenBarrierException()
                }
                // Check if another thread broke the barrier
                if (localBarrierRequest.wasCompleted) {
                    return indexOfArrival // (1..parties-1)
                }
                if (remainingNanos <= 0) {
                    // give-up by timeout
                    // the barrier was not broken nor completed, but this thread gave up, so the
                    // barrier must be broken by this thread
                        breakBarrier()
                    throw TimeoutException()
                }
            }
        }
    }

    /**
     * Returns the number of parties currently waiting at the barrier.
     */
    fun getNumberWaiting(): Int = lock.withLock { barrierRequest.nOfThreadsWaiting }

    /**
     * Returns the number of parties required to trigger this barrier.
     */
    fun getParties(): Int = parties

    /**
     * Returns true if the barrier is in a broken state, false otherwise.
     */
    fun isBroken(): Boolean = lock.withLock { barrierRequest.wasBroken }

    /**
     * Resets the barrier to its initial state.
     */
    fun reset() = lock.withLock {
        // Can't reset a barrier that no thread is waiting for - reusing the same barrier
        if (barrierRequest.nOfThreadsWaiting == 0) return
        breakBarrier()
        // create a new barrier request for the next barrier generation
        barrierRequest = BarrierRequest()
    }

    /**
     * Executes the barrier action if it exists.
     * If the barrier action throws a [Throwable], the barrier is immediately broken and
     * all waiting threads are signaled.
     * This method should only be called inside a thread-safe environment, since it checks and
     * alters the internal state of the barrier.
     */
    private fun executeBarrierAction() {
        barrierAction?.let {
            runCatching {
                it.run()
            }.onFailure {
                breakBarrier()
            }
        }
    }

    /**
     * Signals all waiting threads, at this barrier, that it was broken by
     * another thread.
     * This method should only be called inside a thread-safe environment, since it checks and
     * alters the internal state of the barrier.
     */
    private fun breakBarrier() {
        barrierRequest.wasBroken = true
        barrierCondition.signalAll()
    }
}
