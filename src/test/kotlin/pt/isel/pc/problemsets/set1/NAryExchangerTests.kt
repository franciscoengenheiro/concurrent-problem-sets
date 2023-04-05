package pt.isel.pc.problemsets.set1

import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class NAryExchangerTests {

    companion object {
        private const val N_OF_THREADS = 24
    }

    @Test
    fun `Exchanger should return values received by a thread group`() {
        val groupSize = 3
        val exchanger: NAryExchanger<Int> = NAryExchanger(groupSize)
        // Each thread will have a test willing-to-wait timeout
        val timeout = 1.seconds
        val testHelper = MultiThreadTestHelper(timeout)
        testHelper.createAndStartMultipleThreads(N_OF_THREADS) { it, willingToWaitTimeout ->
            while (!willingToWaitTimeout()) {
                val result = exchanger.exchange(it, Duration.parse("1s")) ?: break
                assertEquals(groupSize, result.size)
            }
        }
        testHelper.join()
    }

    @Test
    fun `Exchanger should return values received by a thread group (with multiple threads)`() {
        val groupSize = 10
        val exchanger: NAryExchanger<Int> = NAryExchanger(groupSize)
        // Each thread will have a test willing-to-wait timeout
        val timeout = 1.seconds
        val testHelper = MultiThreadTestHelper(timeout)
        testHelper.createAndStartMultipleThreads(N_OF_THREADS) { it, willingToWaitTimeout ->
            while (!willingToWaitTimeout()) {
                val result = exchanger.exchange(it, Duration.parse("1s"))
                assertNotNull(result)
                assertEquals(groupSize, result.size)
            }
        }
        testHelper.join()
    }

    @Test
    fun `Exchanger return values should not equal expected values`() {
        TODO()
    }

    @Test
    fun `Exchanger should only operate in thread groups above minimum group size`() {
        assertFailsWith<IllegalArgumentException> {
            val exchanger: NAryExchanger<String> = NAryExchanger(1)
        }
    }

    @Test
    fun `Exchanger should return null if received timeout limit gave by a thread in the group is reached`() {
        TODO()
        // Note: this test also ensures the thread that completes the group does not wait no matter the
        // given timeout duration in the exchange method
        /*val groupSize = 3
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
        ths.forEach { it.join() }*/
    }

    @Test
    fun `Exchanger should throw an exception if a thread inside of a uncompleted group is interrupted`() {
        TODO()
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