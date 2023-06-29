package pt.isel.pc.problemsets.async

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

class CoroutineScopeAndCancellationTests {

    @Test
    fun `coroutine scope only resumes when children complete`() {
        runBlocking {
            coroutineScope {
                launch {
                    delay(1000)
                    logger.info("coroutine-1")
                }
                launch {
                    delay(2000)
                    logger.info("coroutine-2")
                }
                logger.info("CoroutineScope: Before child coroutines")
            }
            logger.info("CoroutineScope: After child coroutines")
        }
    }

    @Test
    fun `cancellation of child coroutine by parent does not cancel parent coroutine`() {
        runBlocking {
            val c1 = launch {
                try {
                    delay(1000)
                } catch (ex: CancellationException) {
                    logger.info("Caught CancellationException")
                    throw ex
                }
            }
            launch {
                try {
                    delay(1000)
                    logger.info("After delay on second child coroutine")
                } catch (ex: CancellationException) {
                    logger.info("Caught CancellationException")
                    throw ex
                }
            }
            delay(500)
            c1.cancel()
        }
    }

    @Test
    fun `cancellation of child coroutine by itself does not cancel parent coroutine`() {
        runBlocking {
            launch {
                try {
                    withTimeout(500) {
                        delay(1000)
                    }
                } catch (ex: CancellationException) {
                    logger.info("Caught CancellationException")
                    throw ex
                }
            }
            launch {
                try {
                    delay(1000)
                    logger.info("After delay on second child coroutine")
                } catch (ex: CancellationException) {
                    logger.info("Caught CancellationException")
                    throw ex
                }
            }
        }
    }

    @Test
    fun `exception other than cancellation on child coroutine does cancel parent coroutine`() {
        assertThrows<RuntimeException> {
            runBlocking {
                launch {
                    try {
                        withTimeout(500) {
                            delay(1000)
                        }
                    } catch (ex: CancellationException) {
                        logger.info("Caught CancellationException")
                        throw RuntimeException("Reacting to cancellation")
                    }
                }
                launch {
                    try {
                        delay(1000)
                        logger.info("After delay on second child coroutine")
                    } catch (ex: CancellationException) {
                        logger.info("Caught CancellationException")
                        throw ex
                    }
                }
            }
        }
    }

    @Test
    fun `using supervisorScope`() {
        runBlocking {
            supervisorScope {
                launch {
                    try {
                        withTimeout(500) {
                            delay(1000)
                        }
                    } catch (ex: CancellationException) {
                        logger.info("Caught CancellationException A")
                        throw RuntimeException("Reacting to cancellation")
                    }
                }
                launch {
                    try {
                        delay(1000)
                        logger.info("After delay on second child coroutine")
                    } catch (ex: CancellationException) {
                        logger.info("Caught CancellationException B")
                        throw ex
                    }
                }
            }
        }
    }

    @Test
    fun `using supervisorScope and a CoroutineExceptionHandler`() {
        runBlocking {
            val exceptionHandler = CoroutineExceptionHandler { _, exception ->
                logger.info("Unhandled exception: {}", exception.message)
            }
            supervisorScope() {
                launch(exceptionHandler) {
                    try {
                        withTimeout(500) {
                            delay(1000)
                        }
                    } catch (ex: CancellationException) {
                        logger.info("Caught CancellationException")
                        throw RuntimeException("Reacting to cancellation")
                    }
                }
                launch(exceptionHandler) {
                    try {
                        delay(1000)
                    } catch (ex: CancellationException) {
                        logger.info("Caught CancellationException")
                        throw ex
                    }
                }
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CoroutineScopeAndCancellationTests::class.java)
    }
}