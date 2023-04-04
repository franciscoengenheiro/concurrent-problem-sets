package pt.isel.pc.problemsets.set1

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class NAryExchangerTest {
    @JvmInline
    private value class ThreadInfo(val name: String)

    private var mapOfCaughtExceptions: ConcurrentMap<Thread, Throwable> = ConcurrentHashMap()
    private val randomIntToString: String
        get() = SecureRandom().nextInt(Int.MAX_VALUE).toString()

    @AfterEach
    fun `throw caught exceptions and clear concurrent map`() {
        mapOfCaughtExceptions.values
            .forEach { throw it }
            .also { mapOfCaughtExceptions.clear() }
    }

    @Test
    fun `Exchanger should return the values received by a thread group`() {
        val groupSize = 3
        val exchanger: NAryExchanger<ThreadInfo> = NAryExchanger(groupSize)
        val ths = List(groupSize) {
            Thread {
                val threadInfo = ThreadInfo("Thread $it")
                try {
                    val result = exchanger.exchange(threadInfo, Duration.parse("5s"))?.toSet()
                    println(result)
                    assertEquals(
                        listOf(
                            ThreadInfo("Thread 0"),
                            ThreadInfo("Thread 1"),
                            ThreadInfo("Thread 2")
                        ).toSet(),
                        result
                    )
                } catch (t: Throwable) {
                    mapOfCaughtExceptions.computeIfAbsent(Thread.currentThread()) { t }
                }
            }
        }
        // Start each thread
        ths.forEach { it.start() }
        // Wait for all threads to finish
        ths.forEach { it.join() }
    }

    @Test
    fun `Exchanger return values should not equal expected values`() {
        val groupSize = 3
        val exchanger: NAryExchanger<ThreadInfo> = NAryExchanger(groupSize)
        val ths = List(groupSize) {
            Thread {
                val threadInfo = ThreadInfo("Thread $it")
                try {
                    val result = exchanger.exchange(threadInfo, Duration.parse("5s"))?.toSet()
                    assertEquals(
                        listOf(
                            ThreadInfo(randomIntToString),
                            ThreadInfo(randomIntToString),
                            ThreadInfo(randomIntToString)
                        ).toSet(),
                        result
                    )
                } catch (t: Throwable) {
                    mapOfCaughtExceptions.computeIfAbsent(Thread.currentThread()) { t }
                }
            }
        }
        // Start each thread
        ths.forEach { it.start() }
        // Wait for all threads to finish
        ths.forEach { it.join() }
        assertFailsWith<AssertionError> {
            mapOfCaughtExceptions.values.first { throw it }
        }
    }

    @Test
    fun `Exchanger should only operate in thread groups above minimum group size`() {
        assertFailsWith<IllegalArgumentException> {
            val exchanger: NAryExchanger<String> = NAryExchanger(1)
        }
    }

    @Test
    fun `Exchanger should return null if received timeout limit gave by a thread in the group is reached`() {
        // Note: this test also ensures the thread that completes the group does not wait no matter the
        // given timeout duration in the exchange method
        val groupSize = 3
        val exchanger: NAryExchanger<ThreadInfo> = NAryExchanger(groupSize)
        val ths = List(groupSize) {
            Thread {
                val threadInfo = ThreadInfo("Thread $it")
                try {
                    val result = exchanger.exchange(threadInfo, Duration.ZERO)
                    if (it + 1 == groupSize) {
                        assertEquals(listOf(ThreadInfo("Thread $it")), result)
                    } else {
                        assertEquals(null, result)
                    }
                } catch (t: Throwable) {
                    mapOfCaughtExceptions.computeIfAbsent(Thread.currentThread()) { t }
                }
            }
        }
        // Start each thread
        ths.forEach { it.start() }
        // Wait for all threads to finish
        ths.forEach { it.join() }
    }

    @Test
    fun `Exchanger should throw an exception if a thread inside of a uncompleted group is interrupted`() {
        val groupSize = 2
        val exchanger: NAryExchanger<ThreadInfo> = NAryExchanger(groupSize)
        val ths = List(groupSize) {
            Thread {
                val threadInfo = ThreadInfo("Thread $it")
                try {
                    val result = exchanger.exchange(threadInfo, Duration.parse("5s"))?.toSet()
                    Thread.currentThread().interrupt()
                    assertEquals(
                        listOf(
                            ThreadInfo("Thread 0"),
                            ThreadInfo("Thread 1"),
                            ThreadInfo("Thread 2")
                        ).toSet(),
                        result
                    )
                } catch (t: Throwable) {
                    mapOfCaughtExceptions.computeIfAbsent(Thread.currentThread()) { t }
                }
            }
        }
        // Start each thread
        ths.forEach { it.start() }
        // Wait for all threads to finish
        ths.forEach { it.join() }
        assertFailsWith<InterruptedException> {
            mapOfCaughtExceptions.values.first { throw it }
        }
    }

    @Test
    fun `Exchanger should not throw an exception if a thread inside of a completed group is interrupted`() {
        TODO()
    }

    @Test
    fun `Exchanger should work with more threads`() {
        TODO()
    }

    @Test
    fun `Exchanger should not keep state between successful exchanges`() {
        TODO()
    }
}