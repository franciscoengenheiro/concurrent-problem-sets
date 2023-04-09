package pt.isel.pc.problemsets.set1

import pt.isel.pc.problemsets.sync.SimpleThreadPool
import pt.isel.pc.problemsets.util.NodeLinkedList
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * Thread pool with a fixed number of worker threads, limited by [maxThreadPoolSize].
 * The thread pool is created with a queue of work items to be executed by the thread pool in a fair manner, using
 * a [NodeLinkedList] for that purpose.
 * @param maxThreadPoolSize the maximum number of worker threads inside the thread pool.
 * @param keepAliveTime maximum time that a worker thread can be idle before being terminated.
 */
class ThreadPoolExecutor(
    private val maxThreadPoolSize: Int,
    private val keepAliveTime: Duration,
) {
    init {
        require(maxThreadPoolSize > 0) { "maxThreadPoolSize must be a natural number" }
    }

    // Each worker thread has a request which is completed when the worker thread is
    // available to execute a work item
    private class WorkerRequest(
        val condition: Condition,
        var canExecute: Boolean = false
    )

    private val lock = ReentrantLock()
    private val workItemsQueue = NodeLinkedList<Runnable>()
    private val workersQueue = NodeLinkedList<WorkerRequest>()
    private var nOfWorkerThreads = 0

    /**
     * Executes the given [workItem] in a worker thread inside the thread pool.
     * @param workItem the work item to be executed
     */
    fun execute(workItem: Runnable): Unit = lock.withLock {
        if (workersQueue.notEmpty) {
            // 1. Give to the worker thread the work item if possible
            // complete worker thread request
            TODO()
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

    private fun getNextWorkItem(): GetWorkItemResult = lock.withLock {
        return if (workItemsQueue.notEmpty) {
            GetWorkItemResult.WorkItem(workItemsQueue.pull().value)
        } else {
            nOfWorkerThreads -= 1
            GetWorkItemResult.Exit
        }
    }

    private sealed class GetWorkItemResult {
        object Exit : GetWorkItemResult()
        class WorkItem(val workItem: Runnable) : GetWorkItemResult()
    }

    private fun workerLoop(firstRunnable: Runnable) {
        var currentRunnable = firstRunnable
        while (true) {
            safeRun(currentRunnable)
            currentRunnable = when (val result = getNextWorkItem()) {
                is GetWorkItemResult.WorkItem -> result.workItem
                GetWorkItemResult.Exit -> return
            }
        }
    }

    fun shutdown() {
        TODO("empty worker loop threads but wait for all threads to finish work or timeout")
    }

    @Throws(InterruptedException::class)
    fun awaitTermination(timeout: Duration): Boolean {
        TODO()
    }

    private fun safeRun(runnable: Runnable) {
        try {
            runnable.run()
        } catch (ex: Throwable) {

        }
    }
}
