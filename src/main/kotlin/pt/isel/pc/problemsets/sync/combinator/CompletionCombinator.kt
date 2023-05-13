package pt.isel.pc.problemsets.sync.combinator

import java.util.concurrent.CompletionStage

/**
 * An abstraction that allows to combine multiple completion stages
 * into a single one.
 */
interface CompletionCombinator {
    /**
     * A completion combinator that returns a completion stage that completes when **all** the input stages complete.
     * The returned stage completes with a list of the results of the input stages.
     * If *any* of the input stages completes exceptionally, the returned stage also completes exceptionally.
     * @param inputStages the input stages to combine.
     * @return the result of the completion stage's combination.
     * @throws IllegalArgumentException if the input list is empty.
     */
    @Throws(IllegalArgumentException::class)
    fun <T> all(inputStages: List<CompletionStage<T>>): CompletionStage<List<T>>

    /**
     * A completion combinator that returns a completion stage that completes when **any** of the input stages completes.
     * The returned stage completes with the result of the first input stage that completes.
     * If *all* the input stages complete exceptionally, the returned stage also completes exceptionally.
     * @param inputStages the input stages to combine.
     * @return the result of the completion stage's combination.
     * @throws AggregationError if all the input stages complete exceptionally.
     * @throws IllegalArgumentException if the input list is empty.
     */
    @Throws(AggregationError::class, IllegalArgumentException::class)
    fun <T> any(inputStages: List<CompletionStage<T>>): CompletionStage<T>
}