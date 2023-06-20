package pt.isel.pc.problemsets.set3.base

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import pt.isel.pc.problemsets.async.SuspendableCountDownLatch
import pt.isel.pc.problemsets.set3.solution.SuspendableAutoCloseable
import pt.isel.pc.problemsets.set3.solution.acceptSuspend
import pt.isel.pc.problemsets.set3.solution.await
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousServerSocketChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * Represents a server to which clients can connect, enter and leave rooms, and send messages.
 * The server uses coroutines to handle potentially blocking operations.
 * @param listeningAddress the address to which the server will be bound.
 * @param listeningPort the port to which the server will be bound.
 * @param nrThreads the number of threads to be used by the server to handle clients. Defaults to single-threaded.
 */
class Server(
    private val listeningAddress: String,
    private val listeningPort: Int,
    nrThreads: Int = 1
) : SuspendableAutoCloseable {

    init {
        require(nrThreads > 0) { "number of threads must be positive" }
    }

    private val multiThreadExecutor: ExecutorService = Executors.newFixedThreadPool(nrThreads)
    private val multiThreadDispatcher: ExecutorCoroutineDispatcher = multiThreadExecutor.asCoroutineDispatcher()
    private val group: AsynchronousChannelGroup = AsynchronousChannelGroup.withThreadPool(multiThreadExecutor)
    private val asyncServerSocketChannel: AsynchronousServerSocketChannel = AsynchronousServerSocketChannel.open(group)
    private val clientContainer = ConnectedClientContainer()
    private val isListening = SuspendableCountDownLatch(1)
    private val shutdownProtocol = CompletableFuture<Boolean>()

    private val acceptCoroutine: Job = CoroutineScope(multiThreadDispatcher).launch {
        logger.info("accept coroutine started")
        asyncServerSocketChannel.bind(InetSocketAddress(listeningAddress, listeningPort))
        println(Messages.SERVER_IS_BOUND)
        isListening.countDown()
        logger.info("server listening on {}:{}", listeningAddress, listeningPort)
        // SupervisorScope is used to prevent the listener coroutine to be canceled when a client coroutine
        // fails or is canceled.
        supervisorScope {
            acceptLoop(this)
        }
    }

    /**
     * Waits until the server is listening for connections.
     */
    suspend fun waitUntilListening() = isListening.await()

    /**
     * Shutdown the server and wait for the operation to end gracefully.
     * If the timeout is reached, the server is closed abruptly.
     * Subsequent calls to this method have no effect.
     * @param timeoutInSeconds the timeout in seconds to wait for the server to shut down.
     * @throws IllegalArgumentException if the [timeoutInSeconds] is negative.
     */
    suspend fun shutdown(timeoutInSeconds: Long) {
        require(timeoutInSeconds >= 0L) { "timeout in seconds must be non-negative" }
        if (shutdownProtocol.isDone) {
            logger.info("server is already shutdown")
            return
        }
        val totalMillis = TimeUnit.SECONDS.toMillis(timeoutInSeconds)
        logger.info("shutting down the server")
        if (totalMillis == 0L) {
            logger.info("cancelling accept coroutine")
            acceptCoroutine.cancel()
            group.shutdownNow() // closes the server socket automatically
        } else {
            val elapseTimeInMillis = measureTimeMillis {
                try {
                    withTimeout(totalMillis) {
                        logger.info("shutting down the client container")
                        clientContainer.shutdown()
                        logger.info("cancelling accept coroutine")
                        acceptCoroutine.cancelAndJoin()
                    }
                } catch (e: CancellationException) {
                    logger.error("timeout reached while waiting for the server to shutdown, closing abruptly")
                }
            }
            group.shutdown()
            val remainingTimeInMillis = totalMillis - elapseTimeInMillis
            if (remainingTimeInMillis > 0L) {
                logger.info("waiting for the server to shutdown gracefully")
                val wasTerminated: Boolean = group.awaitTermination(remainingTimeInMillis, TimeUnit.MILLISECONDS)
                if (!wasTerminated) {
                    logger.info("server was not gracefully shutdown within the received timeout, ignoring")
                }
            } else {
                logger.info("timeout reached while waiting for the server to shutdown, closing abruptly")
            }
            logger.info("explicitly closing the server socket")
            asyncServerSocketChannel.close()
        }
        shutdownProtocol.complete(true)
        logger.info("server shutdown completed")
    }

    /**
     * Shutdown the server and expects to shut down gracefully for as long as necessary.
     */
    suspend fun shutdown() = shutdown(Long.MAX_VALUE)

    /**
     * Synchronizes with the server shutdown protocol termination.
     */
    suspend fun join() = shutdownProtocol.await()

    /**
     * Closes the server abruptly and waits for the operation to end.
     */
    suspend fun exit() {
        shutdown(0)
        join()
    }

    /**
     * Closes the server and waits for the operation to end gracefully.
     */
    override suspend fun close() {
        logger.info("close method called")
        shutdown()
        join()
    }

    /**
     * Accepts new clients in a loop and launches a coroutine to handle each client.
     * @param supervisorScope the supervisor scope to be used by the client coroutines.
     */
    private suspend fun acceptLoop(supervisorScope: CoroutineScope) {
        var clientId = 0
        val roomContainer = RoomContainer()
        val exceptionHandler = CoroutineExceptionHandler { _, exception ->
            logger.error("Unhandled exception: {}, {}", exception.javaClass.name, exception.message)
        }
        while (true) {
            try {
                logger.info("accepting new client")
                val asyncSocketChannel = asyncServerSocketChannel.acceptSuspend()
                supervisorScope.launch(exceptionHandler) {
                    println(Messages.SERVER_ACCEPTED_CLIENT)
                    logger.info("client socket accepted, remote address is {}", asyncSocketChannel.remoteAddress)
                    val client = ConnectedClient(
                        asyncSocketChannel = asyncSocketChannel,
                        id = ++clientId,
                        roomContainer = roomContainer,
                        clientContainer = clientContainer,
                        coroutineScope = this
                    )
                    clientContainer.add(client)
                }
            } catch (e: SocketException) {
                logger.error("SocketException, ending")
                // We assume that an exception means the server was asked to terminate
                break
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(Server::class.java)
    }
}