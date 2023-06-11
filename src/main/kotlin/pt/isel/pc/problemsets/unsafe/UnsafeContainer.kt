package pt.isel.pc.problemsets.unsafe

class UnsafeContainer<T>(private val values: Array<UnsafeValue<T>>) {
    private var index = 0
    fun consume(): T? {
        while (index < values.size) {
            if (values[index].lives > 0) {
                values[index].lives -= 1
                return values[index].value
            }
            index += 1
        }
        return null
    }
}