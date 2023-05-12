package pt.isel.pc.problemsets.set2

import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import pt.isel.pc.problemsets.utils.randomTo
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class CyclicBarrierTests {

    private data class SimpleTask(var done: Boolean = false)

    // tests without concurrency stress:
    @Test
    fun `A barrier with no parties should throw an exception`() {
        assertFailsWith<IllegalArgumentException> {
            CyclicBarrier(1)
        }
    }

    @Test
    fun `Check if indexes of arrival match the order of which the threads entered the barrier with two threads`() {
        val barrier = CyclicBarrier(2)
        val testHelper = MultiThreadTestHelper(30.seconds)
        assertEquals(2, barrier.getParties())
        assertFalse(barrier.isBroken())
        testHelper.createAndStartThread { // th1
            assertEquals(0, barrier.getNumberWaiting())
            assertEquals(1, barrier.await())
        }
        // ensure that th1 is waiting first
        Thread.sleep(2000)
        testHelper.createAndStartThread { // th2
            assertEquals(1, barrier.getNumberWaiting())
            assertEquals(0, barrier.await())
        }
        assertFalse(barrier.isBroken())
        testHelper.join()
    }

    @Test
    fun `A barrier should execute a Runnable`() {
        val parties = 2
        val task = SimpleTask()
        val barrier = CyclicBarrier(parties) { task.done = true }
        val testHelper = MultiThreadTestHelper(10.seconds)
        assertEquals(parties, barrier.getParties())
        assertEquals(0, barrier.getNumberWaiting())
        assertFalse(barrier.isBroken())
        assertFalse { task.done }
        testHelper.createAndStartMultipleThreads(parties) { _, _ ->
            barrier.await()
        }
        testHelper.join()
        assertTrue { task.done }
    }

    @RepeatedTest(3)
    fun `A barrier should be broken because the Runnable execution returned a throwable`() {
        val parties = 10 randomTo 24
        val barrier = CyclicBarrier(parties) {
            // throw any throwable here
            throw IllegalArgumentException()
        }
        val testHelper = MultiThreadTestHelper(10.seconds)
        val latch = CountDownLatch(parties - 1)
        assertFalse(barrier.isBroken())
        testHelper.createAndStartMultipleThreads(parties - 1) { id, _ ->
            assertFailsWith<BrokenBarrierException> {
                latch.countDown()
                barrier.await()
            }
        }
        // ensure all threads created have entered the barrier and are waiting
        latch.await()
        // this thread completes the barrier
        testHelper.createAndStartThread {
            assertFailsWith<IllegalArgumentException> {
                // the throwable thrown by the runnable is propagated to this thread
                barrier.await(Duration.ZERO)
            }
        }
        testHelper.join()
        assertTrue(barrier.isBroken())
    }

    @Test
    fun `A thread that specifies no timeout and does not complete the barrier throws TimeoutException and breaks the barrier`() {
        val barrier = CyclicBarrier(2)
        assertFalse(barrier.isBroken())
        assertFailsWith<TimeoutException> {
            barrier.await(Duration.ZERO)
        }
        assertTrue(barrier.isBroken())
    }

    @Test
    fun `A thread that calls await on a broken barrier throws BrokenBarrierException`() {
        val barrier = CyclicBarrier(2)
        assertFalse(barrier.isBroken())
        val testHelper = MultiThreadTestHelper(10.seconds)
        val th1 = testHelper.createAndStartThread {
            barrier.await()
        }
        // break the barrier
        th1.interrupt()
        testHelper.join()
        assertTrue(barrier.isBroken())
        assertFailsWith<BrokenBarrierException> {
            barrier.await(Duration.ZERO)
        }
    }

    @Test
    fun `A thread that is interrupted on a non-broken barrier throws InterruptedException`() {
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

    @Test
    fun `A thread is interrupted while waiting at the barrier but the barrier was already broken by another interrupted thread`() {
        val barrier = CyclicBarrier(3)
        assertFalse(barrier.isBroken())
        val testHelper = MultiThreadTestHelper(30.seconds)
        val interruptedAfterBrokenCounter = AtomicInteger(0)
        val interruptedCounter = AtomicInteger(0)
        val th1 = testHelper.createAndStartThread {
            runCatching {
                barrier.await()
            }.onFailure {
                when(it) {
                    is InterruptedException -> interruptedCounter.incrementAndGet()
                    is BrokenBarrierException -> interruptedAfterBrokenCounter.incrementAndGet()
                    else -> throw AssertionError("Unexpected exception: $it")
                }
            }
        }
        val th2 = testHelper.createAndStartThread {
            runCatching {
                barrier.await()
            }.onFailure {
                when(it) {
                    is InterruptedException -> interruptedCounter.incrementAndGet()
                    is BrokenBarrierException -> interruptedAfterBrokenCounter.incrementAndGet()
                    else -> throw AssertionError("Unexpected exception: $it")
                }
            }
        }
        // ensure both threads are waiting at the barrier
        Thread.sleep(2000)
        // Interrupt both threads, although it is not possible to ensure th1
        // interrupts before th2
        th1.interrupt()
        th2.interrupt()
        // awake one of the threads to signal the other that the barrier
        // has been broken
        testHelper.join()
        assertEquals(1, interruptedCounter.get())
        assertEquals(1, interruptedAfterBrokenCounter.get())
    }

    @Test
    fun `Reset a barrier with no threads waiting`() {
        val barrier = CyclicBarrier(2)
        assertFalse(barrier.isBroken())
        // reset the barrier
        barrier.reset()
        // ensure a new generation was created
        assertFalse(barrier.isBroken())
    }

    @Test
    fun `Ensure a barrier can be reused after a reset`() {
        val parties = 2
        val barrier = CyclicBarrier(parties)
        val testHelper = MultiThreadTestHelper(10.seconds)
        assertFalse(barrier.isBroken())
        testHelper.createAndStartThread { // th1
            // A thread without timeout breaks the barrier
            assertFailsWith<TimeoutException> {
                barrier.await(Duration.ZERO)
            }
        }
        // Ensure th1 enters the barrier before this thread resets it
        Thread.sleep(2000)
        barrier.reset()
        // By this point a new barrier must have been created, and as such it cannot be broken
        assertFalse(barrier.isBroken())
        repeat(3) {
            testHelper.createAndStartMultipleThreads(parties) { _, _ ->
                barrier.await()
            }
            assertFalse(barrier.isBroken())
        }
        testHelper.join()
    }

    // tests with concurrency stress:
    @RepeatedTest(3)
    fun `Reset a barrier with multiple threads waiting`() {
        val parties = 10 randomTo 24
        val barrier = CyclicBarrier(parties)
        assertFalse(barrier.isBroken())
        val testHelper = MultiThreadTestHelper(30.seconds)
        testHelper.createAndStartMultipleThreads(parties - 1) { _, _ ->
            assertFailsWith<BrokenBarrierException> {
                barrier.await()
            }
        }
        // ensure all threads are waiting at the barrier
        Thread.sleep(5000)
        // reset the barrier
        barrier.reset()
        testHelper.join()
        // ensure a new generation was created
        assertFalse(barrier.isBroken())
    }

    @RepeatedTest(5)
    fun `Check if indexes of arrival match the order of which the threads entered the barrier with multiple threads`() {
        val parties = 10 randomTo 24
        val barrier = CyclicBarrier(parties)
        val testHelper = MultiThreadTestHelper(15.seconds)
        val expectedSet = List(parties) { it }.reversed().toSet()
        val actualIndicesList = ConcurrentLinkedQueue<Int>()
        testHelper.createAndStartMultipleThreads(parties) { _, _ ->
            val actualIndex = barrier.await()
            // Add the value to the indices list
            actualIndicesList.add(actualIndex)
        }
        testHelper.join()
        assertEquals(parties, actualIndicesList.size)
        assertEquals(expectedSet, actualIndicesList.toSet())
    }
}
