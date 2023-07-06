package pt.isel.pc.problemsets.sync.combinator

import java.util.concurrent.CompletionStage

/**
 * A completion combinator that combines the results of multiple [CompletionStage]s depending on the combinator's
 * on the desired behavior.
 */
interface CompletionCombinator {
    /**
     * Returns a [CompletionStage] that completes when **all** of the [inputStages] complete.
     * The returned stage completes with a **list of the results** of the input stages.
     * If *any** of the input stages completes exceptionally, the returned stage also completes exceptionally.
     * @param inputStages the input stages to combine.
     * @return the result of the completion stage's combination.
     * @throws IllegalArgumentException if the input list is empty.
     */
    @Throws(IllegalArgumentException::class)
    fun <T> all(inputStages: List<CompletionStage<T>>): CompletionStage<List<T>>

    /**
     * Returns a [CompletionStage] that completes when **any** of the [inputStages] completes.
     * The returned stage completes with the **result of the first input** stage that completes.
     * If *all* the input stages complete exceptionally, the returned stage also completes exceptionally with an
     * [AggregationError].
     * @param inputStages the input stages to combine.
     * @return the result of the completion stage's combination.
     * @throws AggregationError if all the input stages complete exceptionally.
     * @throws IllegalArgumentException if the input list is empty.
     */
    @Throws(AggregationError::class, IllegalArgumentException::class)
    fun <T> any(inputStages: List<CompletionStage<T>>): CompletionStage<T>
}