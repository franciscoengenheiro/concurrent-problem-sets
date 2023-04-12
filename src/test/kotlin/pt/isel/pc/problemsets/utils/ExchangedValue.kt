package pt.isel.pc.problemsets.utils

/**
 * A class that caracterizes a value given by a [Thread] to a syncronizer.
 * @param threadId the id of the thread.
 * @param repetionId the id of the repetion iteration where the thread gave the value.
 */
data class ExchangedValue(val threadId: Int, val repetionId: Int) {
    companion object {
        val Empty = ExchangedValue(-1, -1)
    }
}