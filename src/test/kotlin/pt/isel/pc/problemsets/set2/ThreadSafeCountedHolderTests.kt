package pt.isel.pc.problemsets.set2

import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import pt.isel.pc.problemsets.utils.randomTo
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

internal class ThreadSafeCountedHolderTests {

    private class TestResource : Closeable {
        val closedCounter = AtomicInteger(0)

        override fun close() {
            // increment the counter each time the close method is called
            closedCounter.incrementAndGet()
        }
    }

    // tests without concurrency stress:
    @Test
    fun `Holder closes a resource that was not used before`() {
        val resource = TestResource()
        val holder = ThreadSafeCountedHolder(resource)
        holder.endUse()
        assertEquals(1, resource.closedCounter.get())
    }

    @Test
    fun `Holder closes a resource after the usage counter reaches zero`() {
        val resource = TestResource()
        val holder = ThreadSafeCountedHolder(resource)
        holder.endUse()
        assertFailsWith<IllegalStateException> {
            holder.endUse()
        }
    }

    @Test
    fun `Use a resource that was closed prior`() {
        val resource = TestResource()
        val holder = ThreadSafeCountedHolder(resource)
        holder.endUse()
        assertNull(holder.tryStartUse())
    }

    @Test
    fun `Holder allows the usage of a resource once`() {
        val resource = TestResource()
        val holder = ThreadSafeCountedHolder(resource)
        // should be the same instance
        assertSame(resource, holder.tryStartUse())
    }

    // tests with concurrency stress:
    @Test
    fun `Several threads try to use the resource`() {
        val resource = TestResource()
        val holder = ThreadSafeCountedHolder(resource)
        val testHelper = MultiThreadTestHelper(10.seconds)
        val nrThreads = 5 randomTo 15
        testHelper.createAndStartMultipleThreads(nrThreads) { _, _ ->
            assertNotNull(holder.tryStartUse())
        }
        testHelper.join()
    }

    @RepeatedTest(3)
    fun `Several thread try to close a resource`() {
        val resource = TestResource()
        val holder = ThreadSafeCountedHolder(resource)
        val testHelper = MultiThreadTestHelper(10.seconds)
        val nrThreads = 10 randomTo 24
        val counter = AtomicInteger(0)
        val exceptionCounter = AtomicInteger(0)
        testHelper.createAndStartMultipleThreads(nrThreads) { _, _ ->
            // only one thread will not throw exception
            runCatching {
                holder.endUse()
            }.onSuccess {
                counter.getAndIncrement()
            }.onFailure {
                exceptionCounter.getAndIncrement()
            }
        }
        testHelper.join()
        assertEquals(1, counter.get())
        assertEquals(nrThreads - 1, exceptionCounter.get())
    }

    @Test
    fun `Several threads use the resource and try to end it's usage several times, always succeding`() {
        val resource = TestResource()
        val holder = ThreadSafeCountedHolder(resource)
        val testHelper = MultiThreadTestHelper(10.seconds)
        val nrThreads = 10
        val exceptionCounter = AtomicInteger(0)
        testHelper.createAndStartMultipleThreads(nrThreads) { _, _ ->
            val value = holder.tryStartUse()
            assertNotNull(value)
            runCatching {
                holder.endUse()
            }.onFailure {
                exceptionCounter.getAndIncrement()
            }
        }
        testHelper.join()
        assertEquals(0, exceptionCounter.get())
    }

    @Test
    fun `A thread uses the resource several times and for the same amount of times threads try to end it's usage`() {
        val resource = TestResource()
        val holder = ThreadSafeCountedHolder(resource)
        val testHelper = MultiThreadTestHelper(10.seconds)
        val nrOfUsages = 10
        val exceptionCounter = AtomicInteger(0)
        // this thread uses the value several times
        val th1 = testHelper.createAndStartThread {
            repeat(nrOfUsages) {
                holder.tryStartUse()
            }
        }
        th1.join()
        // this threads try to decrement its usage counter
        testHelper.createAndStartMultipleThreads(nrOfUsages + 1) { _, _ ->
            runCatching {
                holder.endUse()
            }.onFailure {
                exceptionCounter.getAndIncrement()
            }
        }
        testHelper.join()
        assertEquals(0, exceptionCounter.get())
        // ensure the resource is only closed once
        assertEquals(1, resource.closedCounter.get())
        assertFailsWith<IllegalStateException> {
            holder.endUse()
        }
        assertNull(holder.tryStartUse())
    }

}