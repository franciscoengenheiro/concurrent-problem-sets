package pt.isel.pc.problemsets.set2

import pt.isel.pc.problemsets.sync.combinator.AggregationError
import pt.isel.pc.problemsets.sync.combinator.CompletionCombinator
import pt.isel.pc.problemsets.sync.lockfree.TreiberStack
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [CompletionCombinator] that minimizes the usage of locks to synchronize access to shared state.
 * It uses the Java Memory Model to ensure that the shared state is accessed in a thread-safe manner.
 */
class LockFreeCompletionCombinator : CompletionCombinator {

    @Throws(IllegalArgumentException::class)
    override fun <T> all(inputStages: List<CompletionStage<T>>): CompletionStage<List<T>> {
        require(inputStages.isNotEmpty()) { "inputStages must not be empty" }
        val futureToReturn = CompletableFuture<List<T>>()
        // cannot use a non-thread safe container since it will be shared between multiple threads
        val successStack: TreiberStack<T> = TreiberStack()
        val wasCompleted = AtomicBoolean(false)
        inputStages.forEach {
            it.handle { success: T?, error: Throwable? ->
                val initialObservedStatus = wasCompleted.get()
                // fast-path -> if the future was already completed, do nothing
                if (initialObservedStatus) {
                    return@handle
                }
                // retry-path -> if the future was not completed, try to complete it
                if (success != null) {
                    successStack.push(success)
                } else {
                    requireNotNull(error)
                    // retry-path -> if the future was not completed, try to complete it exceptionally
                    while (true) {
                        val observedStatusOnFailure = wasCompleted.get()
                        if (observedStatusOnFailure) {
                            return@handle
                        }
                        if (wasCompleted.compareAndSet(false, true)) {
                            futureToReturn.completeExceptionally(error)
                            return@handle
                        }
                        // retry
                    }
                }
                if (successStack.size == inputStages.size) {
                    // retry-path -> if the future was not completed, try to complete it successfully
                    while (true) {
                        val observedStatusOnSuccess = wasCompleted.get()
                        if (observedStatusOnSuccess) {
                            return@handle
                        }
                        if (wasCompleted.compareAndSet(false, true)) {
                            futureToReturn.complete(successStack.toList())
                            return@handle
                        }
                        // retry
                    }
                }
            }
        }
        return futureToReturn
    }

    @Throws(AggregationError::class, IllegalArgumentException::class)
    override fun <T> any(inputStages: List<CompletionStage<T>>): CompletionStage<T> {
        require(inputStages.isNotEmpty()) { "inputStages must not be empty" }
        val futureToReturn = CompletableFuture<T>()
        // cannot use a non-thread safe container since it will be shared between multiple threads
        val failureStack: TreiberStack<Throwable> = TreiberStack()
        val wasCompleted = AtomicBoolean(false)
        inputStages.forEach {
            it.handle { success: T?, error: Throwable? ->
                val initialObservedState = wasCompleted.get()
                if (initialObservedState) {
                    return@handle
                }
                if (success != null) {
                    // retry-path -> if the future was not completed, try to complete it successfully
                    while(true) {
                        val observedStateOnSuccess = wasCompleted.get()
                        if (observedStateOnSuccess) {
                            return@handle
                        }
                        if (wasCompleted.compareAndSet(false, true)) {
                            futureToReturn.complete(success)
                            return@handle
                        }
                        // retry
                    }
                } else {
                    requireNotNull(error)
                    failureStack.push(error)
                }
                if (failureStack.size == inputStages.size) {
                    // retry-path -> if the future was not completed, try to complete it exceptionally
                    while (true) {
                        val observedStateOnFailure = wasCompleted.get()
                        if (observedStateOnFailure) {
                            return@handle
                        }
                        if (wasCompleted.compareAndSet(false, true)) {
                            futureToReturn.completeExceptionally(
                                AggregationError("All futures failed", failureStack.toList())
                            )
                            return@handle
                        }
                        // retry
                    }
                }
            }
        }
        return futureToReturn
    }
}