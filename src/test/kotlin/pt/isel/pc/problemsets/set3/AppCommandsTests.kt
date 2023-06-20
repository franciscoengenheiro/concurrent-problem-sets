package pt.isel.pc.problemsets.set3

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.set3.base.App
import pt.isel.pc.problemsets.set3.base.Messages
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals

class AppCommandsTests {

    @Test
    fun `shutdown command should shutdown the server gracefully within timeout`() {
        val seconds = 5
        val output = redirectInputOutputStream("/shutdown $seconds") {
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
    fun `shutdown command with invalid timeout should return an error message`() {
        val output = redirectInputOutputStream("/shutdown -1") {
            runBlocking {
                launch {
                    App.launch(this)
                }
                App.awaitListening()
                delay(1000)
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
        val output = redirectInputOutputStream("/shutdown") {
            runBlocking {
                launch {
                    App.launch(this)
                }
                App.awaitListening()
                delay(1000)
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
        val output = redirectInputOutputStream("/exit") {
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
        val output = redirectInputOutputStream("/exit 1") {
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
    fun `wrong command should return an response from the server`() {
        val output = redirectInputOutputStream("is there any command available?") {
            runBlocking {
                launch {
                    App.launch(this)
                }
                App.awaitListening()
                delay(1000)
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
    fun `command to see available app commands should print all available commands`() {
        val output = redirectInputOutputStream("/commands") {
            runBlocking {
                launch {
                    App.launch(this)
                }
                App.awaitListening()
                delay(1000)
                App.shutdown() // without explicit shutdown, the server will not stop
            }
        }
        assertEquals(listOf(Messages.SERVER_IS_BOUND, Messages.APP_COMMANDS), output)
    }

    @Test
    fun `command to see available app commands does not receive any argument`() {
        val output = redirectInputOutputStream("/commands gibberish") {
            runBlocking {
                launch {
                    App.launch(this)
                }
                App.awaitListening()
                delay(1000)
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

    companion object {

        /**
         * Executes the [test] function with the input and output redirected.
         * @param lines Each line that will be read from the input.
         * @param test Code to test that reads from stdin and writes to stdout.
         * @return Lines written in the output.
         */
        private fun redirectInputOutputStream(vararg lines: String, test: (() -> Unit)? = null): List<String> {
            // save old configuration
            val oldInput = System.`in`
            val oldOutput = System.out
            return runCatching {
                // Set new input
                System.setIn(ByteArrayInputStream(lines.joinToString(System.lineSeparator()).toByteArray()))
                val result = ByteArrayOutputStream()
                // Set new output
                System.setOut(PrintStream(result))
                // Run received test function
                test?.invoke()
                val out = result.toString().split(System.lineSeparator())
                if (out.size > 1 && out.last().isEmpty()) out.dropLast(1) else out
            }.also {
                // Return old configuration
                System.setIn(oldInput)
                System.setOut(oldOutput)
            }.getOrNull() ?: emptyList()
        }
    }
}