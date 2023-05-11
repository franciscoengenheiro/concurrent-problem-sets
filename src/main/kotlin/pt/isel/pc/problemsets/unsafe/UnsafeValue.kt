package pt.isel.pc.problemsets.unsafe

class UnsafeValue<T>(val value: T, initialLives: Int) {
    init {
        require(initialLives > 0) { "initialLives must be strictly positive" }
    }
    var lives = initialLives
}