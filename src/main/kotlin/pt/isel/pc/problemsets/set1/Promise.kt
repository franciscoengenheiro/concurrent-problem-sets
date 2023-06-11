package pt.isel.pc.problemsets.set1

import pt.isel.pc.problemsets.set1.Promise.State
import pt.isel.pc.problemsets.util.TimeoutHelper
import pt.isel.pc.problemsets.util.TimeoutHelper.remainingUntil
import pt.isel.pc.problemsets.util.TimeoutHelper.start
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Provides a [Future] that is explicitly completed, and it can be resolved with a value, rejected with a
 * throwable or canceled.
 * This is an implementation of the
 * [Promise](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Promise)
 * pattern in Kotlin, using the Monitor synchronization style.
 * All methods are thread-safe to ensure only one thread can access or alter the state of the promise at a given time.
 * The promise is initially in the **Pending** [State], and it can be set to:
 * - **Resolved** with the [resolve] method
 * - **Rejected** with the [reject] method
 * - **Cancelled** with the [cancel] method.
 *
 * Once the promise is in a completed state, it cannot be changed.
 */
class Promise<T> : Future<T> {

    private var state: State = State.Pending
    private val lock: Lock = ReentrantLock()
    private val condition = lock.newCondition()

    private sealed class State {
        /**
         * Represents the state where the computation is pending and has not yet produced a result.
         */
        object Pending : State()

        /**
         * Represents the state where the computation has successfully finished and produced a result.
         * @param result the result of the computation.
         */
        class Resolved(val result: Any?) : State()

        /**
         * Represents the state where the computation has failed due to an exception.
         * @param throwable the throwable that caused the computation to fail.
         */
        class Rejected(val throwable: Throwable) : State()

        /**
         * Represents the state where the computation has been cancelled before it could be completed.
         */
        object Cancelled : State()
    }

    /**
     * Attempts to cancel execution of this task. This attempt will fail if the task has already completed,
     * has already been cancelled, or could not be cancelled for some other reason.
     * If successful, and this task has not started when cancel is called, this task should never run.
     * If the task has already started, then the [mayInterruptIfRunning] parameter determines whether the
     * thread executing this task should be interrupted in an attempt to stop the task.
     * After this method returns, subsequent calls to [isDone] will always return true.
     * Subsequent calls to [isCancelled] will always return true if this method returned true.
     * @param mayInterruptIfRunning true, if the thread executing this task should be interrupted,
     * otherwise, in-progress tasks are allowed to complete.
     * In the current implementation, this parameter is ignored.
     * @return false if the task could not be cancelled, typically because it has already completed normally.
     */
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        lock.withLock {
            if (state is State.Pending) {
                state = State.Cancelled
                condition.signalAll()
                return true
            }
            return false
        }
    }

    /**
     * Returns true if this task was cancelled before it completed normally.
     */
    override fun isCancelled(): Boolean = lock.withLock { state is State.Cancelled }

    /**
     * Returns true if this task completed.
     * Completion may be due to normal termination, an exception, or cancellation.
     * In all of these cases, this method will return true.
     */
    override fun isDone(): Boolean = lock.withLock { state !is State.Pending }

    /**
     * Waits as long as necessary for the computation to complete, and then retrieves its result.
     * @throws CancellationException if the task was cancelled.
     * @throws ExecutionException if the task was executed with an exception.
     * @throws InterruptedException if the current thread was interrupted while waiting.
     * @return the result of the completed task.
     */
    @Throws(InterruptedException::class, CancellationException::class, ExecutionException::class)
    override fun get(): T = get(Long.MAX_VALUE, TimeUnit.MILLISECONDS)

    /**
     * Waits for at most the given time for the computation to complete, and then retrieves its result.
     * The method uses [TimeoutHelper] to handle the timeout.
     * @param timeout the maximum time to wait.
     * @param unit the time unit of the timeout argument.
     * @throws TimeoutException if the timeout was exceeded.
     * @throws CancellationException if the task was cancelled.
     * @throws ExecutionException if the task was executed with an exception.
     * @throws InterruptedException if the current thread was interrupted while waiting.
     * @return the result of the completed task.
     */
    @Throws(
        TimeoutException::class,
        InterruptedException::class,
        CancellationException::class,
        ExecutionException::class
    )
    override fun get(timeout: Long, unit: TimeUnit): T {
        lock.withLock {
            // fast-path: the promise was already completed
            if (isDone) {
                return evaluateCompletedState()
            }
            // Do not wait if the promise wasn't completed
            if (TimeoutHelper.noWait(timeout)) {
                throw TimeoutException()
            }
            // wait-path
            val deadline = start(timeout, unit)
            var remaining = remainingUntil(deadline)
            while (true) {
                try {
                    condition.await(remaining, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    if (isDone) {
                        // Arm the interrupt flag in order to not lose the interruption request
                        // If this thread is blocked again it will throw an InterruptedException
                        Thread.currentThread().interrupt()
                        return evaluateCompletedState()
                    }
                    // Giving-up by interruption
                    throw e
                }
                if (isDone) {
                    return evaluateCompletedState()
                }
                remaining = remainingUntil(deadline)
                if (TimeoutHelper.isTimeout(remaining)) {
                    // Giving-up by timeout
                    throw TimeoutException()
                }
            }
        }
    }

    /**
     * Resolves this promise with the given [value].
     */
    fun resolve(value: T) = lock.withLock {
        if (state is State.Pending) {
            state = State.Resolved(value)
            condition.signalAll()
        }
    }

    /**
     * Rejects this promise with the given [reason].
     */
    fun reject(reason: Throwable) = lock.withLock {
        if (state is State.Pending) {
            state = State.Rejected(reason)
            condition.signalAll()
        }
    }

    /**
     * Evaluates the current state of the task and returns the result
     * since it was marked as completed.
     * This method should only be called when the task is in a completed state and in a
     * thread-safe environment.
     * @throws CancellationException if the task was cancelled.
     * @throws ExecutionException if the task was aborted or cancelled.
     * @throws IllegalStateException if the task is still pending.
     * @return the result of the completed task.
     */
    @Throws(CancellationException::class, ExecutionException::class, IllegalStateException::class)
    private fun evaluateCompletedState() = when (val currentState = state) {
        is State.Resolved ->
            @Suppress("UNCHECKED_CAST")
            currentState.result as T
        is State.Rejected -> throw ExecutionException(currentState.throwable)
        is State.Cancelled -> throw CancellationException()
        is State.Pending -> throw IllegalStateException("Illegal state: $currentState")
    }
}