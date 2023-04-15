package pt.isel.pc.problemsets.set1

import pt.isel.pc.problemsets.util.NodeLinkedList
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * Thread pool with a dynamic number of worker threads, limited by [maxThreadPoolSize],
 * using the Monitor synchronization style.
 * The worker threads are created on demand,
 * and are terminated if no work is available and the keep-alive idle time is exceeded.
 * To execute a work item, the [execute] method can be used to submit a [Callable] and retrieve
 * a [Future].
 * The [shutdown] method can be used to prevent new work items from being accepted, but
 * previously submitted work items will still be executed.
 * To syncronize with the shutdown process, one should call [awaitTermination] method.
 * @param maxThreadPoolSize the maximum number of worker threads inside the thread pool.
 * @param keepAliveTime maximum time that a worker thread can be idle before being terminated.
 */
class ThreadPoolExecutorWithFuture(
    private val maxThreadPoolSize: Int,
    private val keepAliveTime: Duration,
) {
    init {
        require(maxThreadPoolSize > 0) { "maxThreadPoolSize must be a natural number" }
    }

    private val lock = ReentrantLock()

    // each request represents a work item to be executed by a worker thread
    private class ExecutionRequest<T>(
        val callable: Callable<T>,
        val result: Promise<T> = Promise()
    )

    // queue of work items to be executed by the worker threads
    // * - Represents a wildcard type argument, and is commonly used the type
    // of is unknown or not important, in this case it is equivalent to: out Any?
    private val requestQueue = NodeLinkedList<ExecutionRequest<*>>()

    // conditions
    private val awaitWorkItemCondition = lock.newCondition()
    private val awaitTerminationCondition = lock.newCondition()

    // internal state
    private var nOfWorkerThreads: Int = 0
    private var nOfWaitingWorkerThreads = 0
    private var inShutdown = false

    /**
     * Executes the given [callable] in a worker thread inside the thread pool
     * @param callable the work item to be executed.
     * @return a [Future] that can be used to retrieve the result of the computation.
     */
    fun <T> execute(callable: Callable<T>): Future<T> = lock.withLock {
        val request: ExecutionRequest<T> = putWorkItem(callable)
        return request.result
    }

    /**
     * Initiates an orderly shutdown in which previously submitted work items are executed,
     * but no new work items will be accepted.
     * This method awakes all worker threads that were waiting for
     * work in order to clear the queue of work items or to terminate.
     * Invocation has no additional effect if already shut down.
     */
    fun shutdown() = lock.withLock {
        if (!inShutdown) {
            inShutdown = true
            awaitWorkItemCondition.signalAll()
        }
    }

    /**
     * Provides a way to syncronize with the shut down of the thread pool executor.
     * @param timeout the maximum time to wait for the thread pool executor to shut down.
     * @return true if the thread pool executor has been shut down, false if it didn't
     * in the given timeout.
     * @throws InterruptedException if the current thread is interrupted while waiting for the
     * thread pool executor to shut down.
     */
    @Throws(InterruptedException::class)
    fun awaitTermination(timeout: Duration): Boolean {
        lock.withLock {
            // fast-path
            if (inShutdown && nOfWorkerThreads == 0)
                return true
            // the thread that called this method does not want to wait
            if (timeout.inWholeNanoseconds == 0L)
                return false
            // wait-path
            var remainingNanos = timeout.inWholeNanoseconds
            while (true) {
                remainingNanos = awaitTerminationCondition.awaitNanos(remainingNanos)
                if (inShutdown && nOfWorkerThreads == 0)
                    return true
                if (remainingNanos <= 0)
                    return false
            }
        }
    }

    /**
     * Places the given [workItem] in the queue of work items to be executed by a worker thread.
     * This method should only be called inside a thread-safe environment, since it checks and
     * alters the internal state of the thread pool.
     * Placing in the queue is done in this order of priority:
     * - If there is a waiting worker thread, the work item is given to that worker thread.
     * - A new thread is created to execute the work item if the maximum number of threads hasn't been reached.
     * - The work item is placed in the queue, and when a thread is available, it will be executed.
     * @param workItem the work item to be executed.
     * @return a [ExecutionRequest] that represents the execution request of the work item.
     */
    private fun <T> putWorkItem(workItem: Callable<T>): ExecutionRequest<T> {
        // Build a request object that will be used to represent the pending result
        val request = ExecutionRequest(workItem)
        if (inShutdown) {
            request.result.reject(
                RejectedExecutionException("Thread pool is in shutdown mode and is not accepting new tasks"))
        } else {
            if (nOfWaitingWorkerThreads > 0) {
                // 1. Give the work item to a waiting worker thread that was already created
                requestQueue.enqueue(request)
                awaitWorkItemCondition.signal()
            } else if (nOfWorkerThreads < maxThreadPoolSize) {
                // 2. If not possible, create a new worker thread
                nOfWorkerThreads += 1
                Thread {
                    workerLoop(request)
                }.start()
            } else {
                // 3. Place the work item in the queue
                requestQueue.enqueue(request)
            }
        }
        return request
    }

    /**
     * Represents a sum type for the result of the [getNextWorkItem] method.
     * It can be either an [Exit] or a [WorkItem]:
     * - The [Exit] result is used to indicate that the worker thread should be terminated.
     * - The [WorkItem] result is used to indicate that the worker thread should execute
     * the given work item and the remaining idle time
     * to wait for the next work item.
     */
    private sealed class GetWorkItemResult {
        /**
         * Represents the termination of a worker thread.
         */
        object Exit : GetWorkItemResult()

        /**
         * Represents a work item to be executed by a worker thread.
         * @param workItem the [ExecutionRequest] request that contains the work item to be executed.
         * @param remainingIdleTime the remaining time that a worker thread can be idle before being terminated.
         */
        class WorkItem<T>(val workItem: ExecutionRequest<T>, val remainingIdleTime: Long) : GetWorkItemResult()
    }

    /**
     * Returns the next work item to be executed by a worker thread.
     * If there's currently no work item in the queue, the worker thread will wait for a work item
     * to be placed, with a maximum wait time of [timeout] nanoseconds,
     * or until the thread pool is in shutdown mode.
     * @param timeout the maximum time to wait for a work item to be placed in the queue.
     * @return [GetWorkItemResult.WorkItem] if there is a work item in the queue, or
     * [GetWorkItemResult.Exit] if the thread pool is in *shutdown* mode,
     * or the timeout is exceeded.
     */
    private fun getNextWorkItem(timeout: Long): GetWorkItemResult {
        lock.withLock {
            // fast-path
            if (requestQueue.notEmpty) {
                return GetWorkItemResult.WorkItem(requestQueue.pull().value, timeout)
            }
            // Terminate this worker thread if the thread pool is in shutdown mode
            // and there are no more work items in the queue
            if (inShutdown) {
                nOfWorkerThreads -= 1
                // If this was the last worker thread, signal that the thread pool has terminated
                if (nOfWorkerThreads == 0) {
                    awaitTerminationCondition.signalAll()
                }
                return GetWorkItemResult.Exit
            }
            // If timeout is 0, the worker thread should be terminated immediately
            // and not wait for a work item to be placed in the queue
            if (timeout == 0L) {
                return GetWorkItemResult.Exit
            }
            // wait-path
            nOfWaitingWorkerThreads += 1
            var remainingNanos = timeout
            while (true) {
                try {
                    remainingNanos = awaitWorkItemCondition.awaitNanos(remainingNanos)
                } catch (e: InterruptedException) {
                    // If the thread is interrupted while waiting, it should be terminated
                    nOfWaitingWorkerThreads -= 1
                    nOfWorkerThreads -= 1
                    return GetWorkItemResult.Exit
                }
                if (requestQueue.notEmpty) {
                    nOfWaitingWorkerThreads -= 1
                    return GetWorkItemResult.WorkItem(requestQueue.pull().value, remainingNanos)
                }
                if (inShutdown) {
                    nOfWaitingWorkerThreads -= 1
                    nOfWorkerThreads -= 1
                    if (nOfWorkerThreads == 0) {
                        // If this was the last worker thread, signal that the thread pool is terminated
                        awaitTerminationCondition.signalAll()
                    }
                    return GetWorkItemResult.Exit
                }
                if (remainingNanos <= 0) {
                    nOfWaitingWorkerThreads -= 1
                    nOfWorkerThreads -= 1
                    // Giving-up by timeout, remove value queue
                    return GetWorkItemResult.Exit
                }
            }
        }
    }

    /**
     * Runs the given [firstRequest] and then, in a loop, waits for a work item to be available and executes it.
     * The loop is terminated when the [getNextWorkItem] returns [GetWorkItemResult.Exit], which means that
     * there isn't any work item available for this worker thread, or the keep-alive time has exceeded.
     * @param firstRequest the first [ExecutionRequest] to be executed by this worker thread.
     */
    private fun <T> workerLoop(firstRequest: ExecutionRequest<T>) {
        var currentRequest = firstRequest
        var remainingNanos = keepAliveTime.inWholeNanoseconds
        while (true) {
            safeRun(currentRequest)
            val result = getNextWorkItem(remainingNanos)
            currentRequest = when (result) {
                is GetWorkItemResult.WorkItem<*> -> ({
                    remainingNanos = result.remainingIdleTime
                    result.workItem
                }) as ExecutionRequest<T>
                GetWorkItemResult.Exit -> return
            }
        }
    }


    /**
     * With the given [request], executes the [Callable] and sets the result of this [ExecutionRequest] to
     * the result of the [Callable], either successfully or with an exception.
     * @param request the [ExecutionRequest] to be executed.
     */
    private fun <T> safeRun(request: ExecutionRequest<T>) {
        try {
            val result = request.callable.call()
            request.result.resolve(result)
        } catch (ex: Throwable) {
            request.result.reject(ex)
        }
    }

}
