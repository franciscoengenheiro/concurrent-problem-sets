package pt.isel.pc.problemsets.set1

import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * A blocking exchange syncronization mechanism that allows [groupSize] threads to exchange values.
 * The threads that call [exchange] will block until the group is completed by another thread.
 * When the group is completed, all threads that called [exchange] will resume their work with
 * the values retrieved from the other threads, including the value brought by them.
 * @param T the type of the values that will be exchanged.
 * @param groupSize the number of threads that will exchange values.
 * @throws IllegalArgumentException if [groupSize] is less than 2. Once created, the size cannot be changed.
 */
class NAryExchanger<T>(private val groupSize: Int) {
    init {
        require(groupSize >= 2) { "Group size cannot be less than 2" }
    }

    private val lock = ReentrantLock()

    // Each request represents a group of threads that are requesting to exchange values
    private class Request<T>(
        val condition: Condition,
        val values: MutableList<T> = mutableListOf(),
        var isGroupCompleted: Boolean = false
    )

    // Internal state
    private var currentRequest = Request<T>(condition = lock.newCondition())
    private var elementsAlreadyInGroup = 0

    /**
     * Allows one thread to exchange values with other threads inside a group.
     * @param value the value that will be exchanged with the values brought by the other threads inside a group.
     * @param timeout the maximum time that this thread is willing to wait for the group to be completed.
     * @return a list of values brought by all threads inside the group where this thread was inserted, including
     * the value brought by this it or null if the deadline is reached.
     * @throws InterruptedException if the current thread is interrupted while waiting for the group to be completed.
     * Note that if the current thread is interrupted but the group was completed by another thread, the current thread
     * will return as expected and not throw [InterruptedException] unless it's blocked again.
     */
    @Throws(InterruptedException::class)
    fun exchange(value: T, timeout: Duration): List<T>? {
        lock.withLock {
            // Fast-path -> The current thread joins the group as the last element
            // and thus completing it
            println("Threads in the group: $elementsAlreadyInGroup")
            if (elementsAlreadyInGroup == groupSize - 1) {
                print("${Thread.currentThread().name} joined and completed the group\n")
                // Complete the group and signal all threads waiting for the group to be completed
                // that this condition is now true
                currentRequest.isGroupCompleted = true
                currentRequest.condition.signalAll()
                currentRequest.values.add(value)
                val values = currentRequest.values.toList()
                // Create a new group request for the upcoming threads
                currentRequest = Request(lock.newCondition())
                elementsAlreadyInGroup = 0
                return values.toList()
            }
            // Wait-path -> The current thread joins the group but does not complete it and
            // thus awais until that condition is true
            elementsAlreadyInGroup++
            var remainingNanos: Long = timeout.inWholeNanoseconds
            currentRequest.values.add(value)
            val localRequest = currentRequest
            while (true) {
                try {
                    // Current thread enters dormant state for a timeout duration
                    remainingNanos = localRequest.condition.awaitNanos(remainingNanos)
                } catch (e: InterruptedException) {
                    if (localRequest.isGroupCompleted) {
                        // Arm the interrupt flag in order to not lose the interruption request
                        // If this thread is blocked again it will throw an InterruptedException
                        Thread.currentThread().interrupt()
                        // This thread cannot giveup since the group was completed
                        return localRequest.values.toList()
                    }
                    // Giving-up by interruption, remove value from the group
                    localRequest.values.remove(value)
                    elementsAlreadyInGroup--
                    throw e
                }
                // The current thread woke up and checks if the group has been completed
                if (localRequest.isGroupCompleted) {
                    return localRequest.values.toList()
                }
                if (remainingNanos <= 0) {
                    // Giving-up by timeout
                    return null
                }
            }
        }
    }
}