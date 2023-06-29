package pt.isel.pc.problemsets.set1

import org.slf4j.LoggerFactory
import pt.isel.pc.problemsets.set1.ThreadPoolExecutor.GetWorkItemResult.Exit
import pt.isel.pc.problemsets.set1.ThreadPoolExecutor.GetWorkItemResult.WorkItem
import pt.isel.pc.problemsets.util.NodeLinkedList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * Thread pool with a dynamic number of worker threads, limited by [maxThreadPoolSize],
 * using the Monitor synchronization style.
 * The worker threads are created on demand,
 * and are terminated if no work is available and the keep-alive idle time is exceeded.
 * To execute a work item, the [execute] method can be used.
 * The [shutdown] method can be used to prevent new work items from being accepted, but
 * previously submitted work items will still be executed.
 * To synchronize with the shutdown process, the [awaitTermination] method
 * can be used.
 * @param maxThreadPoolSize the maximum number of worker threads inside the thread pool.
 * @param keepAliveTime maximum time that a worker thread can be idle before being terminated.
 */
class ThreadPoolExecutor(
    private val maxThreadPoolSize: Int,
    private val keepAliveTime: Duration
) {
    init {
        require(maxThreadPoolSize > 0) { "maxThreadPoolSize must be a natural number" }
    }

    private val lock = ReentrantLock()

    // queue of work items to be executed by the worker threads
    private val workItemsQueue = NodeLinkedList<Runnable>()

    // conditions
    private val awaitWorkItemCondition = lock.newCondition()
    private val awaitTerminationCondition = lock.newCondition()

    // internal state
    private var nOfWorkerThreads: Int = 0
    private var nOfWaitingWorkerThreads = 0
    private var inShutdown = false

    /**
     * Executes the given [workItem] in a worker thread inside the thread pool.
     * @param workItem the work item to be executed.
     */
    fun execute(workItem: Runnable): Unit = lock.withLock {
        if (inShutdown) throw RejectedExecutionException("Thread pool executor is shutting down")
        putWorkItem(workItem)
    }

    /**
     * Initiates an orderly shutdown in which previously submitted work items are executed,
     * but no new work items will be accepted.
     * Invocation has no additional effect if already shut down.
     */
    fun shutdown() = lock.withLock {
        if (!inShutdown) {
            inShutdown = true
            awaitWorkItemCondition.signalAll()
            // if no worker thread is active, the threads waiting for the shutdown will never be signaled,
            // so a signal needs to be sent here
            if (nOfWorkerThreads == 0) {
                awaitTerminationCondition.signalAll()
            }
        }
    }

    /**
     * Provides a way to synchronize with the shutdown process of the thread pool executor.
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
            if (inShutdown && nOfWorkerThreads == 0) {
                return true
            }
            // the thread that called this method does not want to wait
            if (timeout.inWholeNanoseconds == 0L) {
                return false
            }
            // wait-path
            var remainingNanos = timeout.inWholeNanoseconds
            while (true) {
                remainingNanos = awaitTerminationCondition.awaitNanos(remainingNanos)
                if (inShutdown && nOfWorkerThreads == 0) {
                    return true
                }
                if (remainingNanos <= 0) {
                    return false
                }
            }
        }
    }

    /**
     * Places the given [workItem] in the queue of work items to be executed by a worker thread.
     * This method should only be called inside a thread-safe environment, since it checks and
     * alters the internal state of the thread pool.
     * Placing in the queue is done in this order of priority:
     * - If there is a waiting worker thread, the work item is given to that worker thread.
     * - A new thread is created to execute the work item, if the maximum number of threads hasn't been reached.
     * - The work item is placed in the queue, and when a thread is available, it will be executed.
     */
    private fun putWorkItem(workItem: Runnable) {
        if (nOfWaitingWorkerThreads > 0) {
            // 1. Give the work item to a waiting worker thread that was already created
            workItemsQueue.enqueue(workItem)
            awaitWorkItemCondition.signal()
            // nOfWaitingWorkerThreads is decremented by the worker thread that takes the work item
            nOfWaitingWorkerThreads -= 1
        } else if (nOfWorkerThreads < maxThreadPoolSize) {
            // 2. If not possible, create a new worker thread
            nOfWorkerThreads += 1
            Thread {
                workerLoop(workItem)
            }.start()
        } else {
            // 3. Place the work item in the queue
            workItemsQueue.enqueue(workItem)
        }
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
         * @param workItem the work item to be executed by a worker thread.
         * @param remainingIdleTime the remaining time that a worker thread can be idle before being terminated.
         */
        class WorkItem(val workItem: Runnable, val remainingIdleTime: Long) : GetWorkItemResult()
    }

    /**
     * Returns the next work item to be executed by a worker thread.
     * If there's currently no work item in the queue, the worker thread will wait for a work item
     * to be placed, with a maximum wait time of [timeout] nanoseconds,
     * or until the thread pool is in shutdown mode.
     * @param timeout the maximum time to wait for a work item to be placed in the queue.
     * @return [GetWorkItemResult.WorkItem] if there is a work item in the queue, or
     * [GetWorkItemResult.Exit] if the thread pool is in *shutdown* mode
     * or the timeout is exceeded.
     */
    private fun getNextWorkItem(timeout: Long): GetWorkItemResult {
        lock.withLock {
            // fast-path
            if (workItemsQueue.notEmpty) {
                return WorkItem(workItemsQueue.pull().value, timeout)
            }
            // Terminate this worker thread if the thread pool is in shutdown mode
            // and there are no more work items in the queue
            if (inShutdown) {
                nOfWorkerThreads -= 1
                // If this was the last worker thread, signal that the thread pool has terminated
                if (nOfWorkerThreads == 0) {
                    awaitTerminationCondition.signalAll()
                }
                return Exit
            }
            // If timeout is 0, the worker thread should be terminated immediately
            // and not wait for a work item to be placed in the queue
            if (timeout == 0L) {
                return Exit
            }
            // wait-path
            nOfWaitingWorkerThreads += 1
            var remainingNanos = timeout
            while (true) {
                remainingNanos = awaitWorkItemCondition.awaitNanos(remainingNanos)
                // purposely ignoring interruption requests
                if (workItemsQueue.notEmpty) {
                    // if a worker thread was signal with work in the queue,
                    // the counter was decremented by the thread that placed the work item already
                    return WorkItem(workItemsQueue.pull().value, remainingNanos)
                }
                if (inShutdown) {
                    nOfWaitingWorkerThreads -= 1
                    nOfWorkerThreads -= 1
                    if (nOfWorkerThreads == 0) {
                        // If this was the last worker thread, signal that the thread pool is terminated
                        awaitTerminationCondition.signalAll()
                    }
                    return Exit
                }
                if (remainingNanos <= 0) {
                    nOfWaitingWorkerThreads -= 1
                    nOfWorkerThreads -= 1
                    // Giving-up by timeout, remove value from the queue
                    return Exit
                }
            }
        }
    }

    /**
     * Runs the given [firstRunnable] and then, in a loop, waits for a work item to be available and runs it.
     * The loop is terminated when the [getNextWorkItem] returns [GetWorkItemResult.Exit], which means that
     * there isn't any work item available for this worker thread, or the keep-alive time has exceeded.
     * @param firstRunnable the first work item to be executed by this worker thread.
     */
    private fun workerLoop(firstRunnable: Runnable) {
        var currentRunnable: Runnable = firstRunnable
        var remainingNanos = keepAliveTime.inWholeNanoseconds
        while (true) {
            safeRun(currentRunnable)
            val result = getNextWorkItem(remainingNanos)
            currentRunnable = when (result) {
                is WorkItem -> {
                    remainingNanos = result.remainingIdleTime
                    result.workItem
                }
                Exit -> return
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ThreadPoolExecutor::class.java)

        /**
         * Runs the given [runnable] and catches any [Throwable] that might be thrown.
         * @param runnable the code to be executed.
         */
        private fun safeRun(runnable: Runnable) {
            try {
                runnable.run()
            } catch (ex: Throwable) {
                logger.warn("Unexpected exception while running work item, ignoring it")
                // ignoring exception
            }
        }
    }
}