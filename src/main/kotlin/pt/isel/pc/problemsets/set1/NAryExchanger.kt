package pt.isel.pc.problemsets.set1

import util.NodeLinkedList
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

class NAryExchanger<T>(private val groupSize: Int) {
    init {
        require(groupSize >= 2) { "Group size cannot be less than 2" }
    }

    // TODO(each request represents a potential group)
    private val requestQueue = NodeLinkedList<Request<T>>()
    private val lock = ReentrantLock()

    private class Request<T>(
        var possibleValue: T,
        val condition: Condition,
        var isGroupCompleted: Boolean = false
    )
    private var exchangedValueContainer: List<T> = emptyList()
    private var elementsAlreadyInGroup = 0

    @Throws(InterruptedException::class)
    fun exchange(value: T, timeout: Duration): List<T>? {
        lock.withLock {
            // Fast-path -> The current thread joins the group as the last element
            // and thus completing the group
            println("Threads in the group: $elementsAlreadyInGroup")
            if (elementsAlreadyInGroup == groupSize - 1) {
                print("${Thread.currentThread().name} completed the group\n")
                elementsAlreadyInGroup = 0
                return (listOfExchangedValues() + value).also { exchangedValueContainer = it }
            }
            println("${Thread.currentThread().name} joined the group")
            val localRequestNode = requestQueue.enqueue(
                Request(
                    value,
                    lock.newCondition()
                )
            )
            // Wait-path -> The current thread joins the group but does not complete it and
            // thus awais until that condition is true
            elementsAlreadyInGroup++
            var remainingNanos: Long = timeout.inWholeNanoseconds
            while (true) {
                try {
                    // Current thread enters dormant state for a timeout duration
                    remainingNanos = localRequestNode.value.condition.awaitNanos(remainingNanos)
                } catch (e: InterruptedException) {
                    if (localRequestNode.value.isGroupCompleted) {
                        // The current thread was interrupted but cannot giveup
                        Thread.currentThread().interrupt()
                        return exchangedValueContainer
                    }
                    // Giving-up by interruption
                    requestQueue.remove(localRequestNode)
                    throw e
                }
                // The current thread woke up and checks if the group has been completed
                if (localRequestNode.value.isGroupCompleted) {
                    return exchangedValueContainer
                }
                if (remainingNanos <= 0) {
                    // Giving-up by timeout
                    requestQueue.remove(localRequestNode)
                    return null
                }
            }
        }
    }

    private fun listOfExchangedValues(): List<T> {
        var headRequest = requestQueue.headNode
        val list: MutableList<T> = mutableListOf()
        while (headRequest != null) {
            headRequest.value.isGroupCompleted = true
            headRequest.value.condition.signalAll()
            list += headRequest.value.possibleValue
            requestQueue.remove(headRequest)
            headRequest = requestQueue.headNode
        }
        return list
    }
}