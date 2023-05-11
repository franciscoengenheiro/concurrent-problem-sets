package pt.isel.pc.problemsets.sync.lockbased

import pt.isel.pc.problemsets.sync.CompletionCombinator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class LockBasedCompletionCombinator : CompletionCombinator {

    @Throws(IllegalArgumentException::class)
    override fun <T> all(inputFutures: List<CompletionStage<T>>): CompletionStage<List<T>> {
        require(inputFutures.isNotEmpty()) { "inputFutures must not be empty" }
        val futureToReturn = CompletableFuture<List<T>>()
        val listToReturn: MutableList<T> = mutableListOf()
        val lock = ReentrantLock()
        var isDone = false
        inputFutures.forEach { inputFuture ->
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
                    if (listToReturn.size == inputFutures.size) {
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

    override fun <T> any(futures: List<CompletionStage<T>>): CompletionStage<T> {
        TODO("Not yet implemented")
    }

}