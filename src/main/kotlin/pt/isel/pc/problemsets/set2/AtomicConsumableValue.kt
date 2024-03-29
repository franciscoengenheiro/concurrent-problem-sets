package pt.isel.pc.problemsets.set2

import java.util.concurrent.atomic.AtomicInteger

/**
 * Represents a value that can be consumed by multiple threads.
 * @param T the type of the value.
 * @param value the actual value.
 * @param initialLives the number of times that the value can be consumed by a thread.
 */
class AtomicConsumableValue<T>(val value: T, private val initialLives: Int) {
    init {
        require(initialLives > 0) { "initial lives must be a natural number" }
    }

    /**
     * The current number of lives of this [value].
     */
    val lives = AtomicInteger(initialLives)
}