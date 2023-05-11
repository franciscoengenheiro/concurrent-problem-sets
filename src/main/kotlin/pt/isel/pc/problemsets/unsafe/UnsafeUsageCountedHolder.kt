import java.io.Closeable

class UnsafeUsageCountedHolder<T : Closeable>(value: T) {
    private var value: T? = value

    // the instance creation counts as one usage
    private var useCounter: Int = 1
    fun tryStartUse(): T? {
        if (value == null) return null
        useCounter += 1
        return value
    }

    fun endUse() {
        if (useCounter == 0) throw IllegalStateException("Already closed")
        if (--useCounter == 0) {
            value?.close()
            value = null
        }
    }
}