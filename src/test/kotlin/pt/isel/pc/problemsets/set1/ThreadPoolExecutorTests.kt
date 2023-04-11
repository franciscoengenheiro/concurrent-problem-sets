package pt.isel.pc.problemsets.set1

import org.junit.jupiter.api.Test
import java.util.concurrent.ArrayBlockingQueue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ThreadPoolExecutorTests {
    // tests without currency stress:
    @Test
    fun `Single thread pool executor should execute received tasks`() {
        val executor = ThreadPoolExecutor(1, 1.seconds)
        val nOfRunnables = 10
        val expected = List(nOfRunnables) { "task $it" }
        val results = ArrayBlockingQueue<String>(nOfRunnables)
        repeat(nOfRunnables) {
            executor.execute {
                val taskName = "task $it"
                results.add(taskName)
            }
        }
        executor.shutdown()
        executor.awaitTermination(5.seconds)
        assertEquals(expected, results.toList())
    }

    @Test
    fun `Thread pool executor should only operate with number of worker threads above zero`() {
        assertFailsWith<IllegalArgumentException> {
            ThreadPoolExecutor(0, Duration.INFINITE)
        }
    }

    @Test
    fun `Thread pool executor should only operate with keep-alive times above zero`() {
        assertFailsWith<IllegalArgumentException> {
            ThreadPoolExecutor(23, Duration.ZERO)
        }
    }
}