package pt.isel.pc.problemsets.set2

import java.util.concurrent.atomic.AtomicInteger

/**
 * A thread-safe container that allows multiple threads to consume its values using the [consume] method.
 * The container is initialized with a set [UnsafeValue]s, each with a number of lives, that represents the number of times
 * that value can be consumed.
 * @param T the type of the values to be consumed.
 * @param values an array of [UnsafeValue]s.
 * @throws IllegalArgumentException if [values] is empty.
 */
class ThreadSafeContainer<T>(private val values: Array<UnsafeValue<T>>) {
    init {
        require(values.isNotEmpty()) { "values must not be empty" }
    }
    // TODO("remember to use volatile and atomic reference if needed")
    // TODO("also remember that atomic ref is less efficient")
    // TODO("Reminder: volatile does not garantee atomicity but atomic ref does")
    private val index = AtomicInteger(0)

    /**
     * Consumes a value from the [values] container.
     * @return the consumed value or null if there are no more values to consume.
     */
    fun consume(): T? {
        val firstObservedIndex = index.get()
        // fast-path -> There are no more values to be consumed
        if (firstObservedIndex == values.size) return null
        // retry-path -> Retrive a value or retry if not possible
        do {
            // observe the current value of the index and check
            // if there are more values to consume
            val observedIndex = index.get()
            if (observedIndex in values.indices) { // 0..values.size
                val unsafeValue = values[observedIndex]
                // TODO("decrement life and return the value if possible")
                while (true) {
                    val observedLives = unsafeValue.lives.get()
                    val nextLives = if (observedLives > 0) observedLives - 1 else break
                    if (unsafeValue.lives.compareAndSet(observedLives, nextLives))
                        return unsafeValue.value
                    }
            }
            // observe the current index value again
            val observedIndexInOrderToIncrement = index.get()
            // TODO("increment index by 1 if possible")
            val nextIndex = if (observedIndexInOrderToIncrement < values.size) observedIndexInOrderToIncrement + 1 else break
            // try to increment it if observed index value is still the same value that
            // is inside the atomic reference
            index.compareAndSet(observedIndexInOrderToIncrement, nextIndex)
            // retry
        } while (true)
        return null
        /**
        // can't read this value needs to be observed first
        while(index < values.size) {
            // can't read this value needs to be observed first
            if (values[index].lives > 0) {
                // can't read this value needs to be observed first
                values[index].lives -= 1
                // can't read this value needs to be observed first
                return values[index].value
            }
            // can't read this value needs to be observed first
            index += 1
        }
        **/
    }
}