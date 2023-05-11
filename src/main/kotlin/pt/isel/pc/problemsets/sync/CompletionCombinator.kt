package pt.isel.pc.problemsets.sync

import java.util.concurrent.CompletionStage

interface CompletionCombinator {
    fun <T> all(inputFutures: List<CompletionStage<T>>): CompletionStage<List<T>>
    fun <T> any(futures: List<CompletionStage<T>>): CompletionStage<T>
}