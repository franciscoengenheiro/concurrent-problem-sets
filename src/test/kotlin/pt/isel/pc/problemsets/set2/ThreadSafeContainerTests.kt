package pt.isel.pc.problemsets.set2

import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import pt.isel.pc.problemsets.utils.randomTo
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.CyclicBarrier as JavaCyclicBarrier

internal class ThreadSafeContainerTests {

    private val defaultValue = "some value"

    // tests without concurrency stress:
    @Test
    fun `Construct a container with an empty array`() {
        assertFailsWith<IllegalArgumentException> {
            ThreadSafeContainer(emptyArray<AtomicConsumableValue<String>>())
        }
    }

    @Test
    fun `Calling consume on an empty container returns null`() {
        val value = AtomicConsumableValue(defaultValue, 1)
        val container = ThreadSafeContainer(arrayOf(value))
        assertNotNull(container.consume())
        assertNull(container.consume())
    }

    @Test
    fun `One thread uses thread safe container with only one value and multiple lives`() {
        val lives = 100000
        // [0, 1, ..., size - 1]
        val oneElementArray = arrayOf(AtomicConsumableValue(defaultValue, lives))
        val container = ThreadSafeContainer(oneElementArray)
        repeat(lives) {
            assertNotNull(container.consume())
        }
        assertNull(container.consume())
    }

    @RepeatedTest(3)
    fun `One thread uses thread safe container with dynamic values with multiple lives`() {
        val size = 100 randomTo 500
        val lives = 50 randomTo 100
        val valuesArray = Array(size) { AtomicConsumableValue(defaultValue, lives) }
        val container = ThreadSafeContainer(valuesArray)
        repeat(valuesArray.size * lives) {
            assertNotNull(container.consume())
        }
        assertNull(container.consume())
    }

    // tests with concurrency stress:
    @RepeatedTest(3)
    fun `Multiple threads try to consume the only value with one life present in the container`() {
        val oneElementArray = arrayOf(AtomicConsumableValue(defaultValue, 1))
        val container = ThreadSafeContainer(oneElementArray)
        val testHelper = MultiThreadTestHelper(10.seconds)
        val consumedCounter = AtomicInteger(0)
        val notConsumedCounter = AtomicInteger(0)
        val nOfThreads = 10 randomTo 24
        testHelper.createAndStartMultipleThreads(nOfThreads) { _, _ ->
            val consumedValue = container.consume()
            if (consumedValue != null) {
                consumedCounter.incrementAndGet()
            } else {
                notConsumedCounter.incrementAndGet()
            }
        }
        testHelper.join()
        assertNull(container.consume())
        assertEquals(1, consumedCounter.get())
        assertEquals(nOfThreads - 1, notConsumedCounter.get())
    }

    @RepeatedTest(5)
    fun `Multiple threads try to consume from a container with multiple values and a random set of lives`() {
        val size = 10 randomTo 20
        val nOfThreads = 8 randomTo 16
        var totalLivesCounter = 0
        val valuesArray = Array(size) {
            val randomLives = 25 randomTo 100
            AtomicConsumableValue(defaultValue, randomLives)
                .also { totalLivesCounter += randomLives }
        }
        val container = ThreadSafeContainer(valuesArray)
        val testHelper = MultiThreadTestHelper(5.seconds)
        val consumedCounter = AtomicInteger(0)
        val notConsumedCounter = AtomicInteger(0)
        val barrier = JavaCyclicBarrier(nOfThreads)
        testHelper.createAndStartMultipleThreads(nOfThreads) { _, isTestFinished ->
            // Ensure all threads start consuming at the same time
            barrier.await()
            while (!isTestFinished()) {
                val consumedValue = container.consume()
                if (consumedValue != null) {
                    consumedCounter.incrementAndGet()
                } else {
                    notConsumedCounter.incrementAndGet()
                }
            }
        }
        testHelper.join()
        // Ensure the container was emptied
        assertNull(container.consume())
        // Ensure some threads couldn't consume a value
        assertTrue { notConsumedCounter.get() > 0 }
        assertEquals(totalLivesCounter, consumedCounter.get())
    }
}