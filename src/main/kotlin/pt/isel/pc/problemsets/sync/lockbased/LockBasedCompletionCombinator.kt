package pt.isel.pc.problemsets.sync.lockbased

import pt.isel.pc.problemsets.sync.combinator.CombinationError
import pt.isel.pc.problemsets.sync.combinator.CompletionCombinator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A [CompletionCombinator] that uses locks to synchronize access to shared state.
 */
class LockBasedCompletionCombinator : CompletionCombinator {

    @Throws(IllegalArgumentException::class)
    override fun <T> all(inputStages: List<CompletionStage<T>>): CompletionStage<List<T>> {
        require(inputStages.isNotEmpty()) { "inputFutures must not be empty" }
        val futureToReturn = CompletableFuture<List<T>>()
        val listToReturn: MutableList<T> = mutableListOf()
        val lock = ReentrantLock()
        var isDone = false
        inputStages.forEach { inputFuture ->
            inputFuture.handle { success: T?, error: Throwable? ->
                var maybeSuccess: List<T>? = null
                var maybeError: Throwable? = null
                lock.withLock {
                    if (isDone) {
                        return@handle
                    }
                    if (success != null) {
                        listToReturn.add(success)
                    } else {
                        // an error ocurred return the error
                        requireNotNull(error)
                        maybeError = error
                        isDone = true
                        return@withLock
                    }
                    if (listToReturn.size == inputStages.size) {
                        maybeSuccess = listToReturn
                        isDone = true
                    }
                }
                // Reminder: a future can only be completed with a value or an exception,
                // outside the lock, because the completion stage may have an arbitrary number
                // of callbacks that execute after its completion, and we do not know how long they will take
                // to execute. If we completed the future inside the lock, we would be holding the lock
                // indefinitely.
                if (maybeSuccess != null) {
                    futureToReturn.complete(maybeSuccess)
                } else if (maybeError != null) {
                    futureToReturn.completeExceptionally(maybeError)
                }
            }
        }
        return futureToReturn
    }

    @Throws(CombinationError::class, IllegalArgumentException::class)
    override fun <T> any(inputStages: List<CompletionStage<T>>): CompletionStage<T> {
        require(inputStages.isNotEmpty()) { "inputFutures must not be empty" }
        val futureToReturn = CompletableFuture<T>()
        val listOfThrowables: MutableList<Throwable> = mutableListOf()
        val lock = ReentrantLock()
        var isDone = false
        inputStages.forEach { inputFuture ->
            inputFuture.handle { success: T?, error: Throwable? ->
                var maybeSuccess: T? = null
                var maybeError: List<Throwable>? = null
                lock.withLock {
                    if (isDone) {
                        return@handle
                    }
                    if (success != null) {
                        maybeSuccess = success
                        isDone = true
                        return@withLock
                    } else {
                        requireNotNull(error)
                        listOfThrowables.add(error)
                    }
                    // if all futures have failed, return the list of errors
                    if (listOfThrowables.size == inputStages.size) {
                        maybeError = listOfThrowables
                        isDone = true
                    }
                }
                // Reminder: a future can only be completed with a value or an exception,
                // outside the lock, because the completion stage may have an arbitrary number
                // of callbacks that execute after its completion, and we do not know how long they will take
                // to execute. If we completed the future inside the lock, we would be holding the lock
                // indefinitely.
                if (maybeSuccess != null) {
                    futureToReturn.complete(maybeSuccess)
                } else if (maybeError != null) {
                    val catchedThrowables = maybeError
                    requireNotNull(catchedThrowables)
                    futureToReturn.completeExceptionally(
                        CombinationError("All futures failed", catchedThrowables)
                    )
                }
            }
        }
        return futureToReturn
    }

}