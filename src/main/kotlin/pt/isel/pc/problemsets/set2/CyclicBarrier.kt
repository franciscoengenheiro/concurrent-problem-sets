package pt.isel.pc.problemsets.set2

import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * A mechanism used to synchronize a group of threads to wait for each other to reach a specific
 * point before proceeding further. The barrier is *cyclic* in the sense that it can be reused again and again.
 * @param parties the number of threads that will be synchronized by this barrier.
 * @param barrierAction the [Runnable] to execute when the last thread arrives at the barrier.
 * @throws IllegalArgumentException if [parties] is less than 1. Once created, it cannot be changed.
 */
class CyclicBarrier(private val parties: Int, private val barrierAction: Runnable?) {
    constructor(parties: Int) : this(parties, null)

    init {
        require(parties > 0) { "Group size cannot be less than 1" }
    }

    private val lock = ReentrantLock()
    private val triggerBarrierCondition: Condition = lock.newCondition()

    private class BarrierRequest(
        var wasBroken: Boolean = false,
        var wasResetted: Boolean = false
    )

    // Internal state
    private var nOfThreadsWaiting = parties
    private var barrierRequest = BarrierRequest()

    /**
     * Waits until all parties have invoked await on this barrier.
     * @return the arrival index of the current thread, where index [parties] - 1 indicates the first to arrive
     * and zero indicates the last to arrive.
     * @throws InterruptedException if the current thread was interrupted while waiting.
     * @throws BrokenBarrierException if another thread was interrupted or timed out while the current thread was
     * waiting, or the barrier was reset, or the barrier was broken when await was called.
     */
    @Throws(InterruptedException::class, BrokenBarrierException::class)
    fun await(): Int = await(Duration.INFINITE)

    /**
     * Waits until all parties have invoked await on this barrier for up to the specified [timeout] time.
     * @param timeout the maximum time that this thread is willing to wait for the barrier to be triggered.
     * @return the arrival index of the current thread, where index [parties] - 1 indicates the first to arrive
     * and zero indicates the last to arrive.
     * @throws InterruptedException if the current thread was interrupted while waiting.
     * @throws TimeoutException if the specified timeout elapses.
     * @throws BrokenBarrierException if another thread was interrupted or timed out while the current thread was
     * waiting, or the barrier was reset, or the barrier was broken when await was called.
     */
    @Throws(InterruptedException::class, TimeoutException::class, BrokenBarrierException::class)
    fun await(timeout: Duration): Int {
        // fast-path(1) - the barrier was broken
        if (barrierRequest.wasBroken)
            throw BrokenBarrierException()
        // fast-path(2) - the thread that enters is the last thread to arrive and brokes the barrier
        // awaking all other threads
        val indexOfArrival = parties - (nOfThreadsWaiting + 1)
        if (indexOfArrival == 0) {
            brakeBarrier()
            barrierRequest = BarrierRequest()
            return indexOfArrival // 0
        }
        // fast-path(3) - The thread does not want to wait for the barrier to be broken
        if (timeout == Duration.ZERO) {
            throw TimeoutException()
        }
        // wait-path
        var remainingNanos: Long = timeout.inWholeNanoseconds
        val localBarrierRequest = barrierRequest
        // Another thread joins the await condition
        nOfThreadsWaiting++
        while(true) {
            try {
                remainingNanos = triggerBarrierCondition.awaitNanos(remainingNanos)
            } catch (ex: InterruptedException) {
                nOfThreadsWaiting--
                if (!localBarrierRequest.wasResetted) {
                    localBarrierRequest.wasResetted = true
                    brakeBarrier()
                    throw InterruptedException()
                } else {
                    throw BrokenBarrierException()
                }
            }
            // Check if the barrier was resetted by another thread
            if (localBarrierRequest.wasResetted) {
                nOfThreadsWaiting--
                throw BrokenBarrierException()
            }
            // Check if another thread broke the barrier
            if (localBarrierRequest.wasBroken) {
                nOfThreadsWaiting--
                return indexOfArrival // (1..parties-1)
            }
            if (remainingNanos <= 0) {
                nOfThreadsWaiting--
                // give-up by timeout
                throw TimeoutException()
            }
        }
    }

    /**
     * Responsible for signalling all waiting threads, at this barrier, that it was broken by
     * another thread.
     * This method should only be called inside a thread-safe environment, since it checks and
     * alters the internal state of the barrier.
     */
    private fun brakeBarrier() {
        barrierRequest.wasBroken = true
        // TODO(is this necessary?")
        nOfThreadsWaiting = parties
        triggerBarrierCondition.signalAll()
    }

    /**
     * Returns the number of parties currently waiting at the barrier.
     */
    fun getNumberWaiting(): Int = lock.withLock { parties - nOfThreadsWaiting }

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
        barrierRequest.wasResetted = true
        barrierRequest = BarrierRequest()
    }
}