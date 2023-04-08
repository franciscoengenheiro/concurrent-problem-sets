package pt.isel.pc.problemsets.utils

import java.time.Instant
import java.util.concurrent.TimeoutException
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * For a thread to yield the CPU to another thread for a [timeout] duration.
 * The thread must be in a state that allows it to do so.
 * @param th the thread to yield the CPU.
 * @param timeout the maximum duration to yield the CPU.
 */
fun spinUntilTimedWait(th: Thread, timeout: Duration = 1.seconds) {
    val deadline = Instant.now().plusMillis(timeout.inWholeMilliseconds)
    while (th.state != Thread.State.TIMED_WAITING) {
        Thread.yield()
        if (Instant.now().isAfter(deadline)) {
            throw TimeoutException("spinUntilTimedWait exceeded timeout")
        }
    }
}

/**
 * Checks if the values associated with each key in the map are in FIFO (*First in First Out*) order,
 * which means each key has almost the same number of values associated with it have the other keys,
 * with a maximum difference of 5%.
 * @param map the map to check.
 * @return true if the values associated with each key in the map are in FIFO order, false otherwise.
 */
fun <K, V> isInFifoOrder(map: Map<K, List<V>>): Boolean {
    val totalKeys = map.size
    val totalValues = map.values.flatten().size
    val avgNumberOfValuesPerKey = totalValues.toDouble() / totalKeys
    for (listValues in map.values) {
        if (abs(listValues.size - avgNumberOfValuesPerKey) > 0.05 * avgNumberOfValuesPerKey) {
            return false
        }
    }
    return true
}
