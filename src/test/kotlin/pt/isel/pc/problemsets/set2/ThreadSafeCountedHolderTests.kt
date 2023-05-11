package pt.isel.pc.problemsets.set2

import org.junit.jupiter.api.Test
import java.io.Closeable
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThreadSafeCountedHolderTests {

    private class TestResource : Closeable {
        var closed = false
            private set
        override fun close() {
            closed = true
        }
    }

    // tests without concurrency stress:
    @Test
    fun `Holder closes a resource that was not used before`() {
        val resource = TestResource()
        val holder = ThreadSafeCountedHolder(resource)
        holder.endUse()
        assertTrue(resource.closed)
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
}