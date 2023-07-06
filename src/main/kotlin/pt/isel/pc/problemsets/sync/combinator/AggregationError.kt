package pt.isel.pc.problemsets.sync.combinator

/**
 * Represents an error that aggregates a list of [Throwable]s.
 * Provides a [throwables] property to be able to access the list of causes used
 * to construct this error.
 * @param message the description of the error.
 * @param causes the list of [Throwable]s that were wrapped in this error.
 */
class AggregationError(
    message: String,
    causes: List<Throwable>
) : Error(message) {
    val throwables: List<Throwable> by lazy(LazyThreadSafetyMode.PUBLICATION) { causes }
}