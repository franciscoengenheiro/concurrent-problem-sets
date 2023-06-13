package pt.isel.pc.problemsets.set3.base

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import pt.isel.pc.problemsets.set3.solution.AppCommand
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.log

private val logger = LoggerFactory.getLogger("main")

/**
 * Entry point for the application
 * See [Server] and [ConnectedClient] for a high-level view of the architecture.
 */
fun main() {
    logger.info("main started")
    Server("localhost", 8000).use {
        // Shutdown hook to handle SIG_TERM signals (gracious shutdown)
        Runtime.getRuntime().addShutdownHook(
            Thread {
                logger.info("shutdown hook started")
                it.shutdown()
                logger.info("waiting for server to end")
                it.join()
                logger.info("server ended")
            }
        )
        runBlocking {
            logger.info("listening to application commands")
            readCommands(it)
        }
    }
    logger.info("main ended")
}

/**
 * Reads commands from the console and handles them.
 */
suspend fun readCommands(server: Server) {
    while (true) {
        val line = readLineSuspend() ?: break
        // Handle the received command
        when (val command = AppCommand.parse(line)) {
            is AppCommand.ShutdownCommand -> {
                server.shutdown(command.timeout)
                break
            }
            AppCommand.ExitCommand -> {
                server.exit()
                break
            }
            AppCommand.AvailableCommands -> {
                println("Available commands:")
                println("  /shutdown <timeout>")
                println("  /exit")
            }
            is AppCommand.UnknownCommand -> {
                println("Unknown command: ${command.gibberish}")
            }
        }
    }
}

/**
 * Suspendable version of readLine function.
 */
suspend fun readLineSuspend(): String? = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { continuation ->
        try {
            val reader = BufferedReader(InputStreamReader(System.`in`))
            val line = reader.readLine()
            continuation.invokeOnCancellation {
                logger.info("buffered reader closed")
                reader.close()
                logger.info("cancelling readLine coroutine")
            }
            continuation.resume(line)
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
}