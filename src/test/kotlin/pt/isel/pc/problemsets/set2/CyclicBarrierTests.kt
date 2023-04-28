package pt.isel.pc.problemsets.set2

import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import pt.isel.pc.problemsets.utils.randomTo
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class CyclicBarrierTests {

    private data class SimpleTask(var done: Boolean = false)

    @Test
    fun `A barrier with no parties should throw an exception`() {
        assertFailsWith<IllegalArgumentException> {
            CyclicBarrier(0)
        }
    }

    @Test
    fun `A barrier with one thread should not block`() {
        val barrier = CyclicBarrier(1)
        assertEquals(1, barrier.getParties())
        assertEquals(0, barrier.getNumberWaiting())
        assertFalse(barrier.isBroken())
        assertEquals(0, barrier.await(Duration.ZERO))
    }

    @Test
    fun `A barrier with one thread should not block and execute the Runnable`() {
        val task = SimpleTask()
        val barrier = CyclicBarrier(1, Runnable { task.done = true })
        assertEquals(1, barrier.getParties())
        assertEquals(0, barrier.getNumberWaiting())
        assertFalse(barrier.isBroken())
        assertFalse { task.done }
        assertEquals(0, barrier.await(Duration.ZERO))
        assertTrue { task.done }
    }

    @Test
    fun `A thread that specifies no timeout and does not complete the barrier throws TimeoutException`() {
        val barrier = CyclicBarrier(2)
        assertFailsWith<TimeoutException> {
            barrier.await(Duration.ZERO)
        }
    }

    @Test
    fun `A thread that calls await on a broken barrier throws BrokenBarrierException`() {
        val barrier = CyclicBarrier(2)
        assertFalse(barrier.isBroken())
        val testHelper = MultiThreadTestHelper(10.seconds)
        val th1 = testHelper.createAndStartThread { barrier.await() }
        // break the barrier
        th1.interrupt()
        testHelper.join()
        assertTrue(barrier.isBroken())
        assertFailsWith<BrokenBarrierException> {
            barrier.await(Duration.ZERO)
        }
    }

    @Test
    fun `A thread that is interrupted on non broken barrier throws InterruptedException`() {
        val barrier = CyclicBarrier(2)
        assertFalse(barrier.isBroken())
        val testHelper = MultiThreadTestHelper(10.seconds)
        val th1 = testHelper.createAndStartThread {
            assertFailsWith<InterruptedException> {
                barrier.await()
            }
        }
        // break the barrier
        th1.interrupt()
        testHelper.join()
        assertTrue(barrier.isBroken())
    }

    @RepeatedTest(3)
    fun `All threads that are interrupted on a broken barrier throw BrokenBarrierException except the first`() {
        val nOfThreads = 10 randomTo 30
        val barrier = CyclicBarrier(nOfThreads)
        assertFalse(barrier.isBroken())
        val testHelper = MultiThreadTestHelper(10.seconds)
        val th1 = testHelper.createAndStartThread {
            assertFailsWith<InterruptedException> {
                barrier.await()
            }
        }
        testHelper.createAndStartMultipleThreads(nOfThreads - 1) { _, _ ->
            assertFailsWith<BrokenBarrierException> {
                barrier.await()
            }
        }
        // break the barrier
        th1.interrupt()
        testHelper.join()
        assertTrue(barrier.isBroken())
    }

    @RepeatedTest(1)
    fun `A thread that is interrupted on non broken barrier which was completed do not throw exception`() {
        /* NOTE: There's a chance that this test fails due to the fact
         * that the interruption request of the first thread does not come
         * after the second thread completion of the barrier -
         * which is the intended behaviour to test here.
         * Because of that fact, the test is repeated several times and ensures a success rate
         * of at least 99% or else it fails.
         */
        var assertionFailure = 0
        var successCounter = 0
        val repetions = 100
        repeat(repetions) {
            runCatching {
                val nOfThreads = 2
                val barrier = CyclicBarrier(nOfThreads)
                assertFalse(barrier.isBroken())
                val testHelper = MultiThreadTestHelper(5.seconds)
                val done = AtomicBoolean(false)
                val th1 = testHelper.createAndStartThread {
                    val index = barrier.await()
                    assertEquals(barrier.getParties() - 1, index)
                }
                // th2
                testHelper.createAndStartThread {
                    val index = barrier.await()
                    done.set(true)
                    assertEquals(0, index)
                }
                while(!done.get()) {
                    Thread.sleep(0)
                }
                th1.interrupt()
                testHelper.join()
                successCounter++
            }.onFailure {
                if (it is AssertionError) {
                    assertionFailure++
                } else {
                    throw it
                }
            }
        }
        if (assertionFailure / successCounter > 0.01) {
            throw AssertionError("Too many failures in this test")
        }
    }

    // TODO("ensure a barrier is reusable")
}