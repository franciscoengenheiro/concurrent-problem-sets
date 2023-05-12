package pt.isel.pc.problemsets.sync.lockfree

import pt.isel.pc.problemsets.sync.combinator.CompletionCombinator
import java.util.concurrent.CompletionStage

/**
 * A [CompletionCombinator] that minimizes the usage of locks to synchronize access to shared state.
 */
class LockFreeCompletionCombinator : CompletionCombinator {
    override fun <T> all(inputStages: List<CompletionStage<T>>): CompletionStage<List<T>> {
        throw NotImplementedError()
    }

    override fun <T> any(inputStages: List<CompletionStage<T>>): CompletionStage<T> {
        throw NotImplementedError()
    }
}