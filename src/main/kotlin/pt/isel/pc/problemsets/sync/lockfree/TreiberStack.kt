package pt.isel.pc.problemsets.sync.lockfree

import pt.isel.pc.problemsets.stack.Stack
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TreiberStack<T> : Stack<T> {

    private class Node<T>(
        val value: T,
    ) {
        var next: Node<T>? = null
    }

    private val counter = AtomicInteger(0)
    private val head = AtomicReference<Node<T>>(null)
    val size: Int
        get() = counter.get()

    override fun push(value: T) {
        val node = Node(value = value)
        while (true) {
            val observedHead: Node<T>? = head.get()
            node.next = observedHead
            if (head.compareAndSet(observedHead, node)) {
                // increment size
                counter.incrementAndGet()
                return
            }
            // retry
        }
    }

    override fun pop(): T? {
        while (true) {
            val observedHead: Node<T> = head.get() ?: return null
            val observedHeadNext = observedHead.next
            if (head.compareAndSet(observedHead, observedHeadNext)) {
                // decrement size
                counter.decrementAndGet()
                return observedHead.value
            }
            // retry
        }
    }

    fun toList(): List<T> {
        // fast-path -> if the stack is empty, return an empty list
        head.get() ?: return emptyList()
        // retry-path -> if the stack is not empty, try to return a list with its elements
        while (true) {
            val observedHead = head.get() ?: return emptyList()
            val newList = mutableListOf(observedHead.value)
            var current = observedHead.next
            while (current != null) {
                newList.add(current.value)
                current = current.next
            }
            // only return the list if the head did not change
            if (head.compareAndSet(observedHead, observedHead)) {
                return newList
            }
            // else, retry
        }
    }

}