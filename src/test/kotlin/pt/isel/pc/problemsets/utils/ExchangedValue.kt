package pt.isel.pc.problemsets.utils

/**
 * A class that caracterizes a value given by a [Thread] to a syncronizer.
 * To represent the absence of a value, use [Empty] instead.
 * @param threadId the id of the thread. Should be positive or zero.
 * @param repetionId the id of the repetion iteration where the thread gave the value. Should be positive or zero.
 */
data class ExchangedValue(val threadId: Int, val repetionId: Int) {
    companion object {
        val Empty = ExchangedValue(-1, -1)
    }
}