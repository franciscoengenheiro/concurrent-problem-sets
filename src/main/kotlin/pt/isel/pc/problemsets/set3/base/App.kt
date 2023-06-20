package pt.isel.pc.problemsets.set3.base

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import pt.isel.pc.problemsets.async.SuspendableCountDownLatch
import pt.isel.pc.problemsets.set3.solution.AppCommand
import pt.isel.pc.problemsets.set3.solution.readLineSuspend
import pt.isel.pc.problemsets.set3.solution.use

/**
 * Entry point for the application
 * See [Server] and [ConnectedClient] for a high-level view of the project architecture.
 */
object App {
    private val logger = LoggerFactory.getLogger("App")
    private val isListening = SuspendableCountDownLatch(1)
    const val listeningAddress = "localhost"
    const val listeningPort = 8000
    private var launchJob: Job? = null

    @Volatile
    private var launched = false

    /**
     * Shuts down the application gracefully.
     */
    suspend fun shutdown() {
        logger.info("shutting down application")
        launchJob?.cancelAndJoin()
    }

    /**
     * Awaits for the application to start listening for standard input commands.
     * Can also be used to synchronize with the server listening state.
     */
    suspend fun awaitListening() {
        logger.info("awaiting for application to start listening")
        isListening.await()
        logger.info("application is listening")
    }

    /**
     * Launches the application.
     * @param scope the scope in which the application will run.
     */
    suspend fun launch(scope: CoroutineScope) {
        check(launched.not()) { "application already launched" }
        launched = true
        launchJob = scope.launch {
            logger.info("launching application")
            Server(listeningAddress, listeningPort).use {
                // Shutdown hook to handle SIG_TERM signals (gracious shutdown)
                Runtime.getRuntime().addShutdownHook(
                    Thread {
                        runBlocking {
                            logger.info("shutdown hook started")
                            it.shutdown()
                            logger.info("waiting for server to end")
                            it.join()
                            logger.info("server ended")
                        }
                    }
                )
                it.waitUntilListening()
                readCommands(it)
            }
            logger.info("application ended")
        }
    }

    /**
     * Reads commands from the console and handles them.
     */
    private suspend fun readCommands(server: Server) {
        logger.info("listening for application commands")
        isListening.countDown()
        while (true) {
            val line = readLineSuspend() ?: break
            // Handle the received command
            when (val command = AppCommand.parse(line)) {
                is AppCommand.ShutdownCommand -> {
                    logger.info("received app command: {}", command)
                    server.shutdown(command.timeout)
                    break
                }

                AppCommand.ExitCommand -> {
                    logger.info("received app command: {}", command)
                    server.exit()
                    break
                }

                AppCommand.AvailableCommands -> {
                    logger.info("received app command: {}", command)
                    println(Messages.APP_COMMANDS)
                }

                is AppCommand.UnknownCommand -> {
                    logger.info("received unknown app command")
                    println(Messages.unknownAppCommand(command.gibberish))
                }
            }
        }
    }
}