package pt.isel.pc.problemsets.sync.stack

/**
 * A stack that supports push and pop operations.
 */
interface Stack<T> {
    /**
     * Places the given [value] on top of the stack.
     */
    fun push(value: T)

    /**
     * Retrieves the value on top of the stack, or null if the stack is empty.
     */
    fun pop(): T?
}