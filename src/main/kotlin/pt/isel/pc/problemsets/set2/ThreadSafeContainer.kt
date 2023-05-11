package pt.isel.pc.problemsets.set2

import java.util.concurrent.atomic.AtomicInteger

/**
 * A thread-safe container that allows multiple threads to consume its values using the [consume] method.
 * The container is initialized with a set [AtomicValue]s, each with a number of lives, that represents the number of times
 * that value can be consumed by a thread.
 * @param T the type of [AtomicValue]s to be consumed.
 * @param values an array of [AtomicValue]s.
 * @throws IllegalArgumentException if [values] is empty.
 */
class ThreadSafeContainer<T>(private val values: Array<AtomicValue<T>>) {
    private val index = AtomicInteger(0)

    init {
        require(values.isNotEmpty()) { "values cannot be empty" }
    }

    /**
     * Consumes a value from the [values] container.
     * @return the consumed value or null if there are no more values to consume.
     */
    fun consume(): T? {
        val firstObservedIndex = index.get()
        // fast-path -> there are no more values to be consumed
        if (firstObservedIndex == values.size) return null
        // retry-path -> retrive an index or retry if not possible
        while (true) {
            val observedIndex = index.get()
            // if there are more values to be consumed
            if (observedIndex in values.indices) {
                while (true) {
                    // retry-path -> A thread tries to decrement a life from a value or retries if not possible
                    val atomicValue = values[observedIndex]
                    val observedLives = atomicValue.lives.get()
                    val nextLives = if (observedLives > 0) observedLives - 1 else break
                    if (atomicValue.lives.compareAndSet(observedLives, nextLives)) {
                        // if the decrement was successful, return the value
                        return atomicValue.value
                    }
                    // retry
                }
            }
            val nextIndex = if (observedIndex < values.size) observedIndex + 1 else break
            index.compareAndSet(observedIndex, nextIndex)
            // retry
        }
        return null
    }
}