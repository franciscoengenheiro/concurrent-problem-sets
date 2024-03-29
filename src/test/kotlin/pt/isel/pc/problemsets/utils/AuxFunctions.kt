package pt.isel.pc.problemsets.utils

import java.time.Instant
import java.util.concurrent.TimeoutException
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
 * Returns a random number between this [Int] and [end] (inclusive).
 * @param end the end of the range.
 */
@Suppress("UNCHECKED_CAST")
infix fun <T : Number> T.randomTo(end: T): T = (this.toLong()..end.toLong()).random() as T

/**
 * Returns a random string of [length] characters.
 * The string is composed of uppercase and lowercase letters only.
 * @param length the length of the string.
 */
fun randomString(length: Int): String {
    val allowedChars = ('A'..'Z') + ('a'..'z')
    return (1..length)
        .map { allowedChars.random() }
        .joinToString("")
}