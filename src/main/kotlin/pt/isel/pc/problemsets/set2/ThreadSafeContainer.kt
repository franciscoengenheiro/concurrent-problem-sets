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
            val outerObservedIndex = index.get()
            // if there are more values to be consumed:
            if (outerObservedIndex in values.indices) {
                while (true) {
                    val atomicValue = values[outerObservedIndex]
                    val observedLives = atomicValue.lives.get()
                    println(Thread.currentThread().name + " - " + observedLives + " lives in " + index.get() + " index")
                    // create a request to decrement the number of lives or break immediately if not possible
                    // (no more lives left for this value)
                    val nextLives = if (observedLives > 0) observedLives - 1 else break
                    // try to decrement it if observed lives value is still the same value that
                    // is inside the atomic reference
                    if (atomicValue.lives.compareAndSet(observedLives, nextLives)) {
                        println(Thread.currentThread().name + " - " + "decremented to " + nextLives)
                        // if the decrement was successful, return the value
                        return atomicValue.value
                    }
                    // retry-path -> A live could not be decremented from a live, so the thread retries
                }
            }
            // observe the current index value again since it might have been changed,
            // the thread might have entered the inner loop
            val secondOuterObservedIndex = index.get()
            // create a request to increment the index value or break immediately if not possible
            val nextIndex = if (secondOuterObservedIndex < values.size) secondOuterObservedIndex + 1 else break
            // try to increment it if the observed index value is still the same value that
            // is inside the atomic reference
            index.compareAndSet(secondOuterObservedIndex, nextIndex)
            // retry
        }
        println(Thread.currentThread().name + " - " + "found no more values")
        return null
    }
}