package pt.isel.pc.problemsets.set1

import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pt.isel.pc.problemsets.utils.ExchangedValue
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import pt.isel.pc.problemsets.utils.spinUntilTimedWait
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class NAryExchangerTests {

    // Tests without concurrency stress:
    @Test
    fun `Exchanger should return values received by a thread group`() {
        val groupSize = 500
        val expectedValues = List(groupSize) { it }
        val exchanger: NAryExchanger<Int> = NAryExchanger(groupSize)
        val testHelper = MultiThreadTestHelper(5.seconds)
        val timeout = 1.seconds
        testHelper.createAndStartMultipleThreads(groupSize) { it, _ ->
            val result = exchanger.exchange(it, timeout)
            requireNotNull(result)
            assertEquals(groupSize, result.size)
            assertEquals(expectedValues, result.sorted())
        }
        testHelper.join()
    }

    @Test
    fun `Exchanger should only operate in thread groups above minimum group size`() {
        assertFailsWith<IllegalArgumentException> {
            val exchanger: NAryExchanger<String> = NAryExchanger(1)
        }
    }

    @Test
    fun `Exchanger should not throw an exception if a thread inside of a completed group is interrupted`() {
        val exchanger: NAryExchanger<Int> = NAryExchanger(2)
        val testHelper = MultiThreadTestHelper(10.seconds)
        val th1 = testHelper.createAndStartThread {
            exchanger.exchange(0, Duration.INFINITE)
        }
        spinUntilTimedWait(th1, 5.seconds)
        testHelper.createAndStartThread {
            exchanger.exchange(1, Duration.INFINITE)
            th1.interrupt()
        }
        testHelper.join()
    }

    @Test
    fun `Exchanger should throw InterruptedException if a thread inside a uncompleted group is interrupted`() {
        val exchanger: NAryExchanger<Int> = NAryExchanger(2)
        val testHelper = MultiThreadTestHelper(15.seconds)
        val th1 = testHelper.createAndStartThread {
            assertThrows<InterruptedException> {
                exchanger.exchange(0, Duration.INFINITE)
            }
        }
        // Ask the thread 1 to interrupt itself
        th1.interrupt()
        testHelper.join()
    }

    @Test
    fun `Thread which does not want to wait for the exchange operation leaves immediatly`() {
        val exchanger: NAryExchanger<Int> = NAryExchanger(2)
        val testHelper = MultiThreadTestHelper(2.seconds)
        testHelper.createAndStartThread {
            assertNull(exchanger.exchange(0, Duration.ZERO))
        }
        testHelper.join()
    }

    @Test
    fun `Exchanger should discard the values received by threads that were interrupted before a group was formed`() {
        val exchanger: NAryExchanger<Int> = NAryExchanger(2)
        val testHelper = MultiThreadTestHelper(15.seconds)
        val th1 = testHelper.createAndStartThread {
            assertThrows<InterruptedException> {
                exchanger.exchange(0, Duration.INFINITE)
            }
        }
        // Ask the thread 1 to interrupt itself
        th1.interrupt()
        // Form a new group
        testHelper.createAndStartThread {
            assertEquals(listOf(1, 2).toSet(), exchanger.exchange(1, Duration.INFINITE)?.toSet())
        }
        testHelper.createAndStartThread {
            assertEquals(listOf(1, 2).toSet(), exchanger.exchange(2, Duration.INFINITE)?.toSet())
        }
        testHelper.join()
    }

    @Test
    fun `Exchanger should return null if has no timeout and it does not complete a group`() {
        val groupSize = 2
        val testHelper = MultiThreadTestHelper(10.seconds)
        val exchanger: NAryExchanger<String> = NAryExchanger(groupSize)
        testHelper.createAndStartThread {
            val result = exchanger.exchange("value", Duration.ZERO)
            assertNull(result)
        }
        testHelper.join()
    }

    // Tests with concurrency stress:
    @RepeatedTest(5)
    fun `An arbitrary number of threads should be able to exchange values`() {
        val groupSize = 4
        val nOfThreads = 24
        val nOfRepetions = 1000000
        val exchanger = NAryExchanger<ExchangedValue>(groupSize)
        // Create a bidimensional array to store the exchange results
        // nOfRepetion is the n of rows and nOfThreads is n of the columns
        val results = Array(nOfThreads) { Array(nOfRepetions) { ExchangedValue.Empty } }
        val testHelper = MultiThreadTestHelper(10.seconds)
        testHelper.createAndStartMultipleThreads(nOfThreads) { threadId, isTestFinished ->
            // This counter does not need to be thread safe since each thread will have its own counter
            var repetionId = 0
            // Each thread will exchange a value nOfRepetions times
            while (!isTestFinished()) {
                val value = ExchangedValue(threadId, ++repetionId)
                // The exchange method will return null if the isTestFinished for this thread has expired
                // and as such a break is needed to saving null values in the results' array
                val result = exchanger.exchange(value, 1.seconds) ?: continue
                assertEquals(groupSize, result.size)
                results[threadId][repetionId] = value
            }
        }
        // Wait for all threads to finish
        testHelper.join()
        // Check that all threads have exchanged the expected values
        results.indices.forEach { t ->
            results[t].indices.forEach { r ->
                val actual = results[t][r]
                if (actual == ExchangedValue.Empty) return@forEach
                val expected = ExchangedValue(t, r)
                assertEquals(expected, actual)
            }
        }
    }
}