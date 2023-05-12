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
        TODO("Not yet implemented")
    }

}