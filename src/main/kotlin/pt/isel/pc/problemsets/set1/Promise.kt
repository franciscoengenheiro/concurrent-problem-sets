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
 * The promise is initially in **[State.Pending]**, and it can be set to:
 * - **[State.Started]** with the [start] method
 * - **[State.Resolved]** with the [resolve] method
 * - **[State.Rejected]** with the [reject] method
 * - **[State.Cancelled]** with the [cancel] method.
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
         * Represents the state where the computation has started but has not yet produced a result.
         * This optional state is used to prevent the computation from being cancelled after it has started.
         */
        object Started : State()

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
     * Attempts to cancel execution of this task. This attempt will fail if the task has already completed
     * was started, has already been cancelled, or could not be canceled for some other reason.
     * If successful, and this task has not started when cancel is called, this task should never run.
     * If the task has already started, then the [mayInterruptIfRunning] parameter determines whether the
     * thread executing this task should be interrupted in an attempt to stop the task.
     * After this method returns, subsequent calls to [isDone] will always return true.
     * Subsequent calls to [isCancelled] will always return true if this method returned true.
     * @param mayInterruptIfRunning true, if the thread executing this task should be interrupted,
     * otherwise, in-progress tasks are allowed to complete.
     * In the current implementation, this parameter is ignored.
     * @return false if the task could not be cancelled - typically because it has already completed normally - or
     * true otherwise.
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
    override fun isDone(): Boolean = lock.withLock { state !is State.Pending && state !is State.Started }

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
            // fast-path A: the promise was already completed
            if (isDone) {
                return evaluateCompletedState()
            }
            // fast-path B: the timeout is zero
            if (TimeoutHelper.noWait(timeout)) {
                throw TimeoutException()
            }
            // wait-path:
            val deadline = start(timeout, unit)
            var remaining = remainingUntil(deadline)
            while (true) {
                try {
                    condition.await(remaining, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    if (isDone) {
                        // Arm the interrupt flag in order to not lose the interruption request
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
     * Marks this task as started.
     * This state is optional and is used to indicate that the task has started
     * but has not yet produced a result.
     * @throws IllegalStateException if the promise is already started.
     */
    fun start() = lock.withLock {
        check(state is State.Pending) { "The promise was already started" }
        state = State.Started
    }

    /**
     * Resolves this promise with the given [value].
     * @throws IllegalStateException if the promise is already completed.
     */
    fun resolve(value: T) = lock.withLock {
        check(state is State.Pending || state is State.Started) { "Cannot a resolve a completed promise" }
        state = State.Resolved(value)
        condition.signalAll()
    }

    /**
     * Rejects this promise with the given [reason].
     */
    fun reject(reason: Throwable) = lock.withLock {
        check(state is State.Pending || state is State.Started) { "Cannot a reject a completed promise" }
        state = State.Rejected(reason)
        condition.signalAll()
    }

    /**
     * Evaluates the current state of the task and returns the result
     * since it was marked as completed.
     * This method should only be called when the task is in a completed state and in a
     * thread-safe environment since it consults the internal state of the promise.
     * @throws CancellationException if the task was cancelled.
     * @throws ExecutionException if the task was aborted or cancelled.
     * @throws IllegalStateException if the task is still pending or still executing.
     * @return the result of the completed task.
     */
    @Throws(CancellationException::class, ExecutionException::class, IllegalStateException::class)
    private fun evaluateCompletedState() = when (val currentState = state) {
        is State.Pending -> throw IllegalStateException("Task is still pending")
        is State.Started -> throw IllegalStateException("Task is still executing")
        is State.Cancelled -> throw CancellationException()
        is State.Resolved ->
            @Suppress("UNCHECKED_CAST")
            currentState.result as T
        is State.Rejected -> throw ExecutionException(currentState.throwable)
    }
}