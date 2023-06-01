package pt.isel.pc.problemsets.async

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class SuspendableCountDownLatchTests {

    @Test
    fun first() {
        runBlocking {
            val countDownLatch = SuspendableCountDownLatch(10)
            repeat(20) {
                launch {
                    countDownLatch.await()
                    logger.info("Ending awaiting coroutine")
                }
            }
            repeat(11) {
                launch {
                    countDownLatch.countdown()
                }
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SuspendableCountDownLatchTests::class.java)
    }
}