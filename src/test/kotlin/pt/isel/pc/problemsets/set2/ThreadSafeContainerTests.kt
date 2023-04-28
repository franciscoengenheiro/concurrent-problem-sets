package pt.isel.pc.problemsets.set2

import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import pt.isel.pc.problemsets.utils.randomTo
import java.util.concurrent.CyclicBarrier as JavaCyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal class ThreadSafeContainerTests {

    private val defaultValue = "some value"

    // tests without concurrency stress:
    @Test
    fun `Calling consume on an empty container returns null`() {
        val container = ThreadSafeContainer(emptyArray<UnsafeValue<String>>())
        assertNull(container.consume())
    }

    @Test
    fun `One thread uses thread safe container with only one UnsafeValue and multiple lives`() {
        val lives = 100000
        // [0, 1, ..., size - 1]
        val oneElementArray = arrayOf(UnsafeValue(defaultValue, lives))
        val container = ThreadSafeContainer(oneElementArray)
        repeat(lives) {
            assertNotNull(container.consume())
        }
        assertNull(container.consume())
    }

    @RepeatedTest(3)
    fun `One thread uses thread safe container with dynamic UnsafeValues with multiple lives`() {
        val size = 1000 randomTo 5000
        val lives = 1000
        val valuesArray = Array(size) { UnsafeValue(defaultValue, lives) }
        val container = ThreadSafeContainer(valuesArray)
        repeat(valuesArray.size * lives) {
            assertNotNull(container.consume())
        }
        assertNull(container.consume())
    }

    // tests with concurrency stress:
    @RepeatedTest(3)
    fun `Multiple threads try to consume the only UnsafeValue value with one life present in the container`() {
        val oneElementArray = arrayOf(UnsafeValue(defaultValue, 1))
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

    @Test
    fun `Multiple threads try to consume from a container with multiple values and a random set of lives`() {
        val size = 3
        var totalLivesCounter = 0
        val valuesArray = Array(size) {
            val randomLives = 4
            UnsafeValue(defaultValue, randomLives).also { totalLivesCounter += randomLives }
        }
        val container = ThreadSafeContainer(valuesArray)
        val testHelper = MultiThreadTestHelper(5.seconds)
        val nOfThreads = 3
        val consumedCounter = AtomicInteger(0)
        val notConsumedCounter = AtomicInteger(0)
        val barrier = JavaCyclicBarrier(nOfThreads)
        testHelper.createAndStartMultipleThreads(nOfThreads) { _, isTestFinished ->
            // Ensure all threads start consuming at the same time
            barrier.await()
            while(!isTestFinished()) {
                val consumedValue = container.consume()
                if (consumedValue != null) {
                    consumedCounter.incrementAndGet()
                } else {
                    notConsumedCounter.incrementAndGet()
                }
            }
        }
        testHelper.join()
        assertNull(container.consume())
        // Ensure some threads couldn't consume a value
        assertTrue { notConsumedCounter.get() > 0 }
        assertEquals(totalLivesCounter, consumedCounter.get())
    }

}