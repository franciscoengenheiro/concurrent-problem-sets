package pt.isel.pc.problemsets.set2

import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.utils.randomTo
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class ThreadSafeContainerTests {

    // tests without concurrency stress:
    @Test
    fun `Cannot use a thread safe container without values inside `() {
        assertFailsWith<IllegalArgumentException> {
            ThreadSafeContainer<Int>(emptyArray())
        }
    }

    @Test
    fun `One thread uses thread safe container with only one UnsafeValue and multiple lives`() {
        val lives = 100000
        // [0, 1, ..., size - 1]
        val oneElementArray = arrayOf(UnsafeValue("some value", lives))
        val container = ThreadSafeContainer(oneElementArray)
        repeat(lives) {
            assertNotNull(container.consume())
        }
        assertNull(container.consume())
    }

    @RepeatedTest(3)
    fun `One thread uses thread safe container with several UnsafeValues and static number of lives`() {
        val size = 1000 randomTo 2500
        val lives = 1000
        val array = Array(size) { UnsafeValue("some value", lives)}
        // [0, 1, ..., size - 1]
        val container = ThreadSafeContainer(array)
        repeat(array.size * lives) {
            assertNotNull(container.consume())
        }
        assertNull(container.consume())
    }

    // tests with concurrency stress:
}