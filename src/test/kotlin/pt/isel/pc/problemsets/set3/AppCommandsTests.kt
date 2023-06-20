package pt.isel.pc.problemsets.set3

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.set3.base.App
import pt.isel.pc.problemsets.set3.base.Messages
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class AppCommandsTests {

    @Test
    fun `shutdown command should shutdown the server gracefully within timeout`() {
        val seconds = 5
        val output = redirectInOutStream("/shutdown $seconds") {
            runBlocking {
                launch {
                    App.launch(this)
                }
                App.awaitListening()
                delay(1000) // time to receive the response
            }
        }
        assertEquals(listOf(Messages.SERVER_IS_BOUND), output)
    }

    @Test
    fun `shutdown command with invalid timeout should return an error message`() {
        val output = redirectInOutStream("/shutdown -1") {
            runBlocking {
                launch {
                    App.launch(this)
                }
                App.awaitListening()
                delay(1000) // time to receive the response
                App.shutdown() // without explicit shutdown, the server will not stop
            }
        }
        assertEquals(
            listOf(
                Messages.SERVER_IS_BOUND,
                Messages.unknownAppCommand(Messages.SHUTDOWN_COMMAND_INVALID_TIMEOUT)
            ),
            output
        )
    }

    @Test
    fun `shutdown command without timeout should return an error message`() {
        val output = redirectInOutStream("/shutdown") {
            runBlocking {
                launch {
                    App.launch(this)
                }
                App.awaitListening()
                delay(1000) // time to receive the response
                App.shutdown() // without explicit shutdown, the server will not stop
            }
        }
        assertEquals(
            listOf(
                Messages.SERVER_IS_BOUND,
                Messages.unknownAppCommand(Messages.SHUTDOWN_COMMAND_TIMEOUT_NOT_SPECIFIED)
            ),
            output
        )
    }

    @Test
    fun `exit command should shutdown the server abruptly`() {
        val output = redirectInOutStream("/exit") {
            runBlocking {
                launch {
                    App.launch(this)
                }
                App.awaitListening()
                delay(1000)
            }
        }
        assertEquals(listOf(Messages.SERVER_IS_BOUND), output)
    }

    @Test
    fun `exit command does not receive any argument`() {
        val output = redirectInOutStream("/exit 1") {
            runBlocking {
                launch {
                    App.launch(this)
                }
                App.awaitListening()
                delay(1000)
            }
        }
        assertEquals(
            listOf(
                Messages.SERVER_IS_BOUND,
                Messages.unknownAppCommand(Messages.EXIT_COMMAND_DOES_NOT_HAVE_ARGUMENTS)
            ),
            output
        )
    }

    @Test
    fun `command to see available app commands should print all available commands`() {
        val output = redirectInOutStream("/commands") {
            runBlocking {
                launch {
                    App.launch(this)
                }
                App.awaitListening()
                delay(1000) // time to receive the response
                App.shutdown() // without explicit shutdown, the server will not stop
            }
        }
        assertEquals(listOf(Messages.SERVER_IS_BOUND, Messages.APP_COMMANDS), output)
    }

    @Test
    fun `command to see available app commands does not receive any argument`() {
        val output = redirectInOutStream("/commands gibberish") {
            runBlocking {
                launch {
                    App.launch(this)
                }
                App.awaitListening()
                delay(1000) // time to receive the response
                App.shutdown() // without explicit shutdown, the server will not stop
            }
        }
        assertEquals(
            listOf(
                Messages.SERVER_IS_BOUND,
                Messages.unknownAppCommand(Messages.AVAILABLE_COMMANDS_COMMAND_DOES_NOT_HAVE_ARGUMENTS)
            ),
            output
        )
    }

    @Test
    fun `wrong command should return an response from the server`() {
        val output = redirectInOutStream("any command available in the server?") {
            runBlocking {
                launch {
                    App.launch(this)
                }
                App.awaitListening()
                delay(1000) // time to receive the response
                App.shutdown() // without explicit shutdown, the server will not stop
            }
        }
        assertEquals(
            listOf(
                Messages.SERVER_IS_BOUND,
                Messages.unknownAppCommand(Messages.SEE_APP_COMMANDS)
            ),
            output
        )
    }

    @Test
    fun `App can only be launched once`() {
        val latch = CountDownLatch(1)
        val testHelper = MultiThreadTestHelper(10.seconds)
        assertFailsWith<IllegalStateException> {
            testHelper.createAndStartThread {
                runBlocking {
                    latch.await()
                    App.launch(this)
                }
            }
            testHelper.createAndStartThread {
                runBlocking {
                    latch.await()
                    App.launch(this)
                }
            }
            Thread.sleep(2000)
            latch.countDown()
            testHelper.join()
        }
    }

    companion object {
        /**
         * Executes the [test] function with the input and output redirected.
         * @param lines Lines to write in the input.
         * @param test Code to test that reads from stdin and writes to stdout.
         * @return Lines written in the output.
         */
        fun redirectInOutStream(vararg lines: String, test: () -> Unit): List<String> {
            val oldInput = System.`in`
            val oldOutput = System.out
            return runCatching {
                System.setIn(ByteArrayInputStream(lines.joinToString(System.lineSeparator()).toByteArray()))
                val result = ByteArrayOutputStream()
                // Set new output
                System.setOut(PrintStream(result))
                test()
                val out = result.toString().split(System.lineSeparator())
                return if (out.size > 1 && out.last().isEmpty()) out.dropLast(1) else out
            }.also {
                // Restore old input and output
                System.setIn(oldInput)
                System.setOut(oldOutput)
            }.getOrNull() ?: emptyList()
        }
    }
}