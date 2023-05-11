package pt.isel.pc.problemsets.set2

import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import pt.isel.pc.problemsets.utils.randomTo
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class CyclicBarrierTests {

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
        println(interruptedCounter.get())
        println(interruptedAfterBrokenCounter.get())
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

    @Test
    fun `Check if indexes of arrival match the order of which the threads entered the barrier with multiple threads`() {
        val parties = 2
        val barrier = CyclicBarrier(parties)
        val testHelper = MultiThreadTestHelper(15.seconds)
        val expectedIndicesOfArrival = List(parties) { it }.reversed()
        val indicesMap = mutableMapOf<Int, Int>()
        // Create a map where the key represents the thread id,
        // and the value represents the expected index of arrival
        // If parties = 3, then the map will be: {0: 2, 1: 1, 2: 0}
        for (index in 0 until parties) {
            indicesMap[index] = expectedIndicesOfArrival[index]
        }
        val counter = AtomicInteger(0)
        testHelper.createAndStartMultipleThreads(parties) { _, _ ->
            val threadId = counter.getAndIncrement()
            // TODO("reminder: here lays a check-then-act error -> between retrieving the counter
            //  value and entering the barrier, another thread might have done that first")
            // TODO("how can I ensure the thread that retrieves the counter, must be the one
            //  to enter, cannot use a lock otherwise it will keep it for the duration of
            //   of the barrier await")
            val actualIndex = barrier.await()
            val expectedIndex = indicesMap[threadId]
            requireNotNull(expectedIndex)
            assertEquals(expectedIndex, actualIndex)
        }
        testHelper.join()
    }

    @Test
    fun `Several barrier generations are created with the several Runnables`() {
        val parties = 20
        val nrOfGenerations = 10
        val simpleTask = SimpleTask()
        val barrier = CyclicBarrier(parties) {
            // TODO("how to add several runnables?")
        }
        repeat(nrOfGenerations) { genId ->
            val testHelper = MultiThreadTestHelper(10.seconds)
            testHelper.createAndStartMultipleThreads(parties) { _, _ ->
                assertFalse { simpleTask.done }
                val indexOfArrival = barrier.await()
                if (indexOfArrival == 0) {
                    assertTrue { simpleTask.done }
                }
            }
            testHelper.join()
        }
    }
}
