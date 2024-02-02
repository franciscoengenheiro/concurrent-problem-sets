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
        var wasOpened: Boolean = false
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
            if (barrierRequest.wasBroken) {
                throw BrokenBarrierException()
            }
            val indexOfArrival = parties - (barrierRequest.nOfThreadsWaiting + 1)
            // fast-path(2) - the thread that enters is the last thread to arrive at an unbroken barrier
            if (indexOfArrival == 0) {
                executeBarrierActionAndUpdate()
                barrierCondition.signalAll()
                barrierRequest = BarrierRequest()
                return 0
            }
            // fast-path(3) - the thread does not want to wait for the barrier to be broken
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
                    if (localBarrierRequest.wasOpened) {
                        // the barrier was opened by another thread,
                        // so the thread must return successfully while keeping the interrupt request alive
                        Thread.currentThread().interrupt()
                        return indexOfArrival // (1..parties-1)
                    } else if (localBarrierRequest.wasBroken) {
                        // the barrier was broken by another thread
                        throw BrokenBarrierException()
                    } else {
                        // the barrier was not opened nor broken, but this thread gave up, so the
                        // barrier must be broken by this thread
                        breakBarrier()
                        throw ex
                    }
                }
                // Check if another thread broke the barrier
                if (localBarrierRequest.wasOpened) {
                    return indexOfArrival // (1..parties-1)
                }
                // Check if the barrier was broken by another thread
                if (localBarrierRequest.wasBroken) {
                    throw BrokenBarrierException()
                }
                if (remainingNanos <= 0) {
                    // give-up by timeout
                    // the barrier was not broken nor opened, but this thread gave up, so the
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
     * If any parties are currently waiting at the barrier, they will return with a [BrokenBarrierException].
     * The current barrier request is always discarded,
     * and a new barrier request is created.
     */
    fun reset() = lock.withLock {
        if (barrierRequest.nOfThreadsWaiting > 0) {
            breakBarrier()
        }
        // create a new barrier request for the next barrier generation
        barrierRequest = BarrierRequest()
    }

    /**
     * Executes the [barrierAction] if it exists.
     * If its execution throws a [Throwable], the barrier is immediately broken and
     * all waiting threads are signaled.
     * The barrier is opened if the [barrierAction] is null or if it does not throw any [Throwable] when executed.
     * This method should only be called inside a thread-safe environment, since it checks and
     * alters the internal state of the barrier.
     */
    private fun executeBarrierActionAndUpdate() {
        val barrierActionRef = barrierAction
        if (barrierActionRef == null) {
            barrierRequest.wasOpened = true
        } else {
            runCatching {
                barrierActionRef.run()
            }.onFailure {
                breakBarrier()
                // propagate this throwable to the thread that executed
                // this runnable
                throw it
            }.onSuccess {
                // the barrier action was executed successfully
                // mark the barrier as opened
                barrierRequest.wasOpened = true
            }
        }
    }

    /**
     * Signals all waiting threads, at this barrier, that it was broken by
     * another thread and create a new generation.
     * This method should only be called inside a thread-safe environment, since it checks and
     * alters the internal state of the barrier.
     */
    private fun breakBarrier() {
        barrierRequest.wasBroken = true
        barrierCondition.signalAll()
    }
}