package pt.isel.pc.problemsets.set2

import java.io.Closeable
import kotlin.jvm.Throws

/**
 * A thread-safe holder that has an internal counter that specifies how many times the value was used.
 * The value is automatically closed when the counter reaches zero.
 * @param T the type of the value that is being held and that implements the [Closeable](https://docs.oracle.com/javase/8/docs/api/java/io/Closeable.html) interface.
 * @param value the value to be held.
 */
class ThreadSafeCountedHolder<T : Closeable>(value: T) {
    private var value: T? = value
    // the instance creation counts as one usage
    // TODO("use volatile or atomic reference")
    private var useCounter: Int = 1

    /**
     * Tries to use the value. If the value is used, the internal counter is incremented.
     * @return the value if it is not null, null otherwise.
     */
    fun tryStartUse(): T? {
        if (value == null) return null
        useCounter += 1
        return value
    }

    /**
     * Ends the use of the value.
     * If the internal counter reaches zero, the value is closed and set to null.
     * @throws IllegalStateException if the value is already closed.
     */
    @Throws(IllegalStateException::class)
    fun endUse() {
        check(useCounter > 0) { "The value is already closed."}
        if (--useCounter == 0) {
            value?.close()
            value = null
        }
    }
}