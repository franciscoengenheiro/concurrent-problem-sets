package pt.isel.pc.problemsets.set1

import pt.isel.pc.problemsets.util.TimeoutHelper
import pt.isel.pc.problemsets.util.TimeoutHelper.remaining
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
 * Represents the result of an asynchronous computation.
 * Methods are provided to check if the computation is complete, to wait for its completion, and to retrieve
 * the result of the computation.
 * The result can only be retrieved using method [get] (with or without *timeout*) when the computation has
 * completed, blocking if necessary until it is ready. Cancellation is performed by the [cancel] method.
 * Additional methods are provided to determine if the task completed normally or was cancelled,
 * with the [isDone] and [isCancelled] methods, respectively.
 * Once a computation has completed, the computation **cannot be** cancelled.
 * @param T the type of the value that will be returned by the [get] method.
 */
class Promise<T> : Future<T> {

    private sealed class State {
        object Pending : State()
        class Resolved(val result: Any?) : State()
        class Rejected(val exception: Throwable) : State()
        object Cancelled : State()
    }

    private var state: State = State.Pending
    private val lock: Lock = ReentrantLock()
    private val condition = lock.newCondition()

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
    override fun isCancelled(): Boolean = state is State.Cancelled

    /**
     * Returns true if this task completed.
     * Completion may be due to normal termination, an exception, or cancellation.
     * In all of these cases, this method will return true.
     */
    override fun isDone(): Boolean = state !is State.Pending

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
     * Waits if necessary for at most the given time for the computation to complete, and then retrieves its result.
     * @param timeout the maximum time to wait.
     * @param unit the time unit of the timeout argument.
     * @throws TimeoutException if the timeout was exceeded.
     * @throws CancellationException if the task was cancelled.
     * @throws ExecutionException if the task was executed with an exception.
     * @throws InterruptedException if the current thread was interrupted while waiting.
     * @return the result of the completed task.
     */
    @Throws(TimeoutException::class, InterruptedException::class, CancellationException::class, ExecutionException::class)
    override fun get(timeout: Long, unit: TimeUnit): T {
        lock.withLock {
            // fast-path
            // The value was already computed
            if (isDone) {
                return evaluateCompletedState()
            }
            // Do not wait if the task wasn't completed
            if (TimeoutHelper.noWait(timeout)) {
                throw TimeoutException()
            }
            // wait-path
            val deadline = start(timeout, unit)
            var remaining = remaining(deadline)
            while (state == State.Pending) {
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
                remaining = remaining(deadline)
                if (TimeoutHelper.isTimeout(remaining)) {
                    // Giving-up by timeout
                    throw TimeoutException()
                }
            }
            return evaluateCompletedState()
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
     * @throws CancellationException if the task was cancelled.
     * @throws ExecutionException if the task was aborted or cancelled.
     * @throws IllegalStateException if the task is still pending.
     * @return the result of the completed task.
     */
    @Throws(CancellationException::class, ExecutionException::class, IllegalStateException::class)
    private fun evaluateCompletedState() = when (val currentState = state) {
        is State.Resolved -> currentState.result as T
        is State.Rejected -> throw ExecutionException(currentState.exception)
        is State.Cancelled -> throw CancellationException()
        is State.Pending -> throw IllegalStateException("Unexpected state: $currentState")
    }
}