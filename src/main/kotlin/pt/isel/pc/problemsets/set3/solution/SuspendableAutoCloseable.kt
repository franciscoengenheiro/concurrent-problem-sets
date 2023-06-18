package pt.isel.pc.problemsets.set3.solution

/**
 * Represents a suspendable version of the [AutoCloseable] interface.
 * An object that may hold resources (such as file or socket handles)
 * until it is closed. The [close] method of an [SuspendableAutoCloseable]
 * object is called automatically when exiting a [use] block, or
 * manually when exceptional circumstances arise.
 * Such construction ensures prompt release, avoiding resource exhaustion exceptions and errors that may otherwise occur.
 */
interface SuspendableAutoCloseable {
    /**
     * Closes this resource, relinquishing any underlying resources.
     * This method is invoked automatically on objects managed by the
     * *try-with-resources* statements.
     */
    suspend fun close()
}
