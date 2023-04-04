package pt.isel.pc.problemsets.set1

import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

class NAryExchanger<T>(private val groupSize: Int) {
    init {
        require(groupSize >= 2) { "Group size cannot be less than 2" }
    }

    private class Request<T>(
        val condition: Condition,
        val values: MutableList<T> = mutableListOf(),
        var isGroupCompleted: Boolean = false
    )

    private val lock = ReentrantLock()

    // Internal state
    private var currentRequest = Request<T>(condition = lock.newCondition())
    private var elementsAlreadyInGroup = 0

    @Throws(InterruptedException::class)
    fun exchange(value: T, timeout: Duration): List<T>? {
        lock.withLock {
            // Fast-path -> The current thread joins the group as the last element
            // and thus completing the group
            println("Threads in the group: $elementsAlreadyInGroup")
            if (elementsAlreadyInGroup == groupSize - 1) {
                print("${Thread.currentThread().name} completed the group\n")
                // Complete the group and signal all threads waiting for the group to be completed
                currentRequest.isGroupCompleted = true
                currentRequest.condition.signalAll()
                // Register the value brought by this thread
                currentRequest.values.add(value)
                val values = currentRequest.values.toList()
                // Create a new group request
                currentRequest = Request(lock.newCondition())
                // Reset the number of elements in the group
                elementsAlreadyInGroup = 0
                return values.toList()
            }
            println("${Thread.currentThread().name} joined the group")
            // Wait-path -> The current thread joins the group but does not complete it and
            // thus awais until that condition is true
            elementsAlreadyInGroup++
            var remainingNanos: Long = timeout.inWholeNanoseconds
            // Register the value brought by this thread
            currentRequest.values.add(value)
            val localRequest = currentRequest
            while (true) {
                try {
                    // Current thread enters dormant state for a timeout duration
                    remainingNanos = localRequest.condition.awaitNanos(remainingNanos)
                } catch (e: InterruptedException) {
                    if (localRequest.isGroupCompleted) {
                        // The current thread was interrupted but cannot giveup since the group
                        // was completed
                        Thread.currentThread().interrupt()
                        return localRequest.values.toList()
                    }
                    // Giving-up by interruption
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