package pt.isel.pc.problemsets.set3.base

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import pt.isel.pc.problemsets.set3.solution.SuspendableAutoCloseable
import pt.isel.pc.problemsets.set3.solution.acceptSuspend
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousServerSocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Represents a server to which clients can connect, enter and leave rooms, and send messages.
 * The server uses coroutines to handle potentially blocking operations.
 * @param listeningAddress the address to which the server will be bound.
 * @param listeningPort the port to which the server will be bound.
 * @param nThreads the number of threads to be used by the server to handle clients. Defaults to single-threaded.
 */
class Server(
    private val listeningAddress: String,
    private val listeningPort: Int,
    nThreads: Int = 1
) : SuspendableAutoCloseable {

    init {
        require(nThreads > 0) { "number of threads must be positive" }
    }

    private val multiThreadExecutor: ExecutorService = Executors.newFixedThreadPool(nThreads)
    private val multiThreadDispatcher: ExecutorCoroutineDispatcher = multiThreadExecutor.asCoroutineDispatcher()
    private val group: AsynchronousChannelGroup = AsynchronousChannelGroup.withThreadPool(multiThreadExecutor)
    private val asyncServerSocketChannel: AsynchronousServerSocketChannel = AsynchronousServerSocketChannel.open(group)
    private val clientContainer = ConnectedClientContainer()
    private val isListening = CountDownLatch(1)

    private val acceptCoroutine: Job = CoroutineScope(multiThreadDispatcher).launch {
        logger.info("listening coroutine started")
        asyncServerSocketChannel.bind(InetSocketAddress(listeningAddress, listeningPort))
        println(Messages.SERVER_IS_BOUND)
        isListening.countDown()
        logger.info("server listening on {}:{}", listeningAddress, listeningPort)
        val exceptionHandler = CoroutineExceptionHandler { _, exception ->
            logger.error("Unhandled exception: {}, {}", exception.javaClass.name, exception.message)
        }
        // SupervisorScope is used to prevent the listener coroutine to be canceled when a client coroutine
        // fails or is canceled.
        supervisorScope {
            launch(exceptionHandler) {
                acceptLoop(this)
            }
        }
    }

    /**
     * Waits until the server is listening for connections.
     */
    fun waitUntilListening() = isListening.await()

    /**
     * Shutdown the server and wait for the operation to end gracefully.
     * If the timeout is reached, the server is forcefully shut down.
     * @param timeoutInSeconds the timeout in seconds to wait for the server to shut down.
     */
    suspend fun shutdown(timeoutInSeconds: Long) {
        require(timeoutInSeconds >= 0L) { "timeout in seconds must be non-negative" }
        logger.info("shutting down the client container")
        clientContainer.shutdown()
        logger.info("cancelling accept coroutine")
        acceptCoroutine.cancelAndJoin()
        val totalMillis = TimeUnit.SECONDS.toMillis(timeoutInSeconds)
        logger.info("shutting down the server")
        if (totalMillis == 0L) {
            group.shutdownNow()
        } else {
            group.shutdown()
            delay(totalMillis) // awaitTermination would block the thread
            val wasTerminated: Boolean = group.isTerminated
            if (!wasTerminated) {
                logger.info("server shutdown timed out, forcing shutdown")
                group.shutdownNow()
            }
        }
        logger.info("explicitly closing the server socket")
        asyncServerSocketChannel.close()
        logger.info("server shutdown completed")
    }

    /**
     * Shutdown the server and expects to shut down gracefully for as long as necessary.
     */
    suspend fun shutdown() = shutdown(Long.MAX_VALUE)

    /**
     * Synchronizes with the server shutdown protocol.
     */
    suspend fun join() = acceptCoroutine.join()

    /**
     * Closes the server abruptly.
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
     * The listening coroutine that accepts new clients as they connect and
     * creates a new coroutine to handle each client.
     * @param coroutineScope the scope of the coroutine.
     */
    private suspend fun acceptLoop(coroutineScope: CoroutineScope) {
        var clientId = 0
        val roomContainer = RoomContainer()
        while (true) {
            logger.info("accepting new client")
            val asyncSocketChannel = asyncServerSocketChannel.acceptSuspend()
            coroutineScope.launch {
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
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(Server::class.java)
    }
}