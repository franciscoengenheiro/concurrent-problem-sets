package pt.isel.pc.problemsets.set3.base

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("main")

/**
 * Entry point for the application
 * See [Server] and [ConnectedClient] for a high-level view of the architecture.
 */
fun main() {
    logger.info("main started")
    // By default, we listen on port 8080 of all interfaces
    val server = Server("0.0.0.0", 8080)

    // Shutdown hook to handle SIG_TERM signals (gracious shutdown)
    Runtime
        .getRuntime()
        .addShutdownHook(
            Thread {
                logger.info("shutdown hook started")
                server.shutdown()
                logger.info("waiting for server to end")
                server.join()
                logger.info("server ended")
            }
        )
    server.join()
    logger.info("server ending")
}