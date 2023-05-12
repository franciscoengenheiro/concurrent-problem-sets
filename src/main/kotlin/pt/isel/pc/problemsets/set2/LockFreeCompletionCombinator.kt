package pt.isel.pc.problemsets.set2

import pt.isel.pc.problemsets.sync.combinator.CombinationError
import pt.isel.pc.problemsets.sync.combinator.CompletionCombinator
import pt.isel.pc.problemsets.sync.lockfree.TreiberStack
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.Throws

/**
 * A [CompletionCombinator] that minimizes the usage of locks to synchronize access to shared state.
 * It uses the Java Memory Model to ensure that the shared state is accessed in a thread-safe manner.
 */
class LockFreeCompletionCombinator : CompletionCombinator {

    @Throws(IllegalArgumentException::class)
    override fun <T> all(inputStages: List<CompletionStage<T>>): CompletionStage<List<T>> {
        require(inputStages.isNotEmpty()) { "inputStages must not be empty" }
        val futureToReturn = CompletableFuture<List<T>>()
        // cannot be a mutable list since it will be shared across a multi-thread environment
        val treiberStack: TreiberStack<T> = TreiberStack()
        val wasCompleted: AtomicBoolean = AtomicBoolean(false)
        inputStages.forEach {
            it.handle { success: T?, error: Throwable? ->
                while (true) {
                    val observedStatus = wasCompleted.get()
                    if (observedStatus) {
                        return@handle
                    }
                    if (success != null) {
                        treiberStack.push(success)
                    } else {
                        requireNotNull(error)
                        futureToReturn.completeExceptionally(error)
                    }
                }
            }
        }





        TODO()
    }

    @Throws(CombinationError::class, IllegalArgumentException::class)
    override fun <T> any(inputStages: List<CompletionStage<T>>): CompletionStage<T> {
        TODO()
    }
}