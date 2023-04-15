package pt.isel.pc.problemsets.util

import java.util.concurrent.TimeUnit

/**
 * Utility class to handle timeouts in Java.
 */
object TimeoutHelper {
    /**
     * Returns true if the timeout is zero.
     * @param timeout the timeout to check.
     */
    fun noWait(timeout: Long): Boolean = timeout == 0L

    /**
     * Calculates the deadline for a timeout from a given timeunit.
     * @param duration the duration of the timeout.
     * @param timeUnit the time unit of the [duration].
     * @returns the deadline in milliseconds.
     */
    fun start(duration: Long, timeUnit: TimeUnit): Long =
        start(timeUnit.toMillis(duration))

    /**
     * Calculates the deadline for a timeout.
     * @param timeout the timeout in milliseconds.
     * @returns the deadline in milliseconds.
     */
    private fun start(timeout: Long): Long = System.currentTimeMillis() + timeout

    /**
     * Calculates the remaining time to the deadline in milliseconds.
     * @param target the deadline in milliseconds.
     */
    fun remainingUntil(target: Long): Long = target - System.currentTimeMillis()

    /**
     * Returns true if the remaining time is zero or negative.
     * @param remaining the remaining time in milliseconds.
     */
    fun isTimeout(remaining: Long): Boolean = remaining <= 0
}