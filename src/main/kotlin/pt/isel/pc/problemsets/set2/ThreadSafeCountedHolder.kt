package pt.isel.pc.problemsets.set2

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A thread-safe holder that has an internal counter that keeps track of how many times the value was used.
 * The value is automatically closed when the counter reaches zero.
 * @param T the type of the value that is being held and that implements the [Closeable](https://docs.oracle.com/javase/8/docs/api/java/io/Closeable.html) interface.
 * @param value the value to be held.
 */
class ThreadSafeCountedHolder<T : Closeable>(value: T) {
    private val value: AtomicReference<T> = AtomicReference(value)

    // the instance creation counts as one usage
    private val useCounter: AtomicInteger = AtomicInteger(1)

    /**
     * Tries to use the value. If the value is used, the internal counter is incremented.
     * @return the value if it is not null, null otherwise.
     */
    fun tryStartUse(): T? {
        // fast-path -> the value is already null, which means the resource was closed
        value ?: return null
        // retry-path -> the value is not closed, so this thread tries to increment the usage counter
        while (true) {
            // since the value variable is marked as volatile, the Java Memory Model garantees that
            // a writing to this variable happens-before this next read.
            value ?: return null
            val observedCounter = useCounter.get()
            val newCounterValue = if (observedCounter > 0) {
                observedCounter + 1
            } else {
                return null
            }
            if (useCounter.compareAndSet(observedCounter, newCounterValue)) {
                return value.get()
            }
            // retry
        }
    }

    /**
     * Ends the use of the value.
     * If the internal counter reaches zero, the value is closed and set to null.
     * @throws IllegalStateException if the value is already closed.
     */
    @Throws(IllegalStateException::class)
    fun endUse() {
        // fast-path -> the value is already null
        val initialObservedCounter = useCounter.get()
        if (initialObservedCounter == 0) {
            throw IllegalStateException("The value is already closed.")
        }
        // retry-path -> the value is not null,
        // so the thread tries to decrement the usage counter if possible
        while (true) {
            val observedCounter = useCounter.get()
            val newCounterValue = if (observedCounter > 0) {
                observedCounter - 1
            } else {
                throw IllegalStateException("The value is already closed.")
            }
            if (useCounter.compareAndSet(observedCounter, newCounterValue)) {
                val observedCounterAfterDec = useCounter.get()
                if (observedCounterAfterDec == 0) {
                    while(true) {
                        val observedValue = value.get()
                            ?: // the value was already closed by another thread in the meantime
                            return
                        if (value.compareAndSet(observedValue, null)) {
                            // the value will be closed by this thread
                            observedValue.close()
                            return
                        }
                        // retry
                    }
                }
            }
            // retry
        }
    }
}