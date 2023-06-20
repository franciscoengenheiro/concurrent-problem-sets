package pt.isel.pc.problemsets.set3

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.test.assertFailsWith

class MutexExampleTests {

    @Test
    fun `suspending while holding a mutex behavior`() {
        val mutex = Mutex()

        suspend fun innerFunction() {
            logger.info("suspending while holding a mutex")
            delay(3000) // while suspended, this coroutine is holding the mutex
            logger.info("after delay")
        }

        runBlocking {
            launch {
                mutex.withLock {
                    logger.info("'main' coroutine acquired the mutex")
                    innerFunction()
                    logger.info("'main' coroutine released the mutex")
                }
            }
            delay(1000)
            launch {
                mutex.withLock {
                    logger.info("another coroutine acquired the mutex")
                }
            }
        }
    }

    @Test
    fun `mutex is not reentrant`() {
        val mutex = Mutex()

        suspend fun innerFunction() {
            logger.info("a coroutine tries to acquire the mutex while holding it")
            // without timeout, this coroutine would be suspended indefinitely
            withTimeout(3000) {
                mutex.withLock {
                    logger.info("supposedly unreachable code")
                }
            }
            logger.info("after timeout")
        }

        runBlocking {
            launch {
                mutex.withLock {
                    logger.info("'main' coroutine acquired the mutex")
                    assertFailsWith<TimeoutCancellationException> {
                        innerFunction()
                    }
                    logger.info("'main' coroutine released the mutex")
                }
            }
        }
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(MutexExampleTests::class.java)
    }
}