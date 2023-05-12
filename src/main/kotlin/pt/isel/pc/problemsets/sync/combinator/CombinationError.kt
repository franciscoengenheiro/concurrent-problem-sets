package pt.isel.pc.problemsets.sync.combinator

/**
 * Represents an error that combines multiple errors in a single one.
 * As a [throwables] property to be able to access the list of causes.
 * @param message the message of the error.
 * @param causes the list of causes that were wrapped in this error.
 */
class CombinationError(
    message: String,
    causes: List<Throwable>
) : Error(message) {
    val throwables: List<Throwable> by lazy(LazyThreadSafetyMode.PUBLICATION) { causes }
}