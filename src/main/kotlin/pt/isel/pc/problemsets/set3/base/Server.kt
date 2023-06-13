package pt.isel.pc.problemsets.set3.base

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import pt.isel.pc.problemsets.set3.solution.acceptSuspend
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousServerSocketChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Represents a server to which clients can connect, enter and leave rooms, and send messages.
 */
class Server(
    private val listeningAddress: String,
    private val listeningPort: Int,
    nThreads: Int = 1
) : AutoCloseable {

    init {
        require(nThreads > 0) { "number of threads must be positive" }
    }

    private val multiThreadExecutor: ExecutorService = Executors.newFixedThreadPool(nThreads)
    private val multiThreadDispatcher: ExecutorCoroutineDispatcher = multiThreadExecutor.asCoroutineDispatcher()
    private val group: AsynchronousChannelGroup = AsynchronousChannelGroup.withThreadPool(multiThreadExecutor)
    private val asyncServerSocketChannel: AsynchronousServerSocketChannel = AsynchronousServerSocketChannel.open(group)

    fun shutdown(timeout: Long) {
        logger.info("cancelling accept coroutine")
        runBlocking {
            clientContainer.shutdown()
        }
        acceptCoroutine.cancel()
        logger.info("shutting down the server")
        if (timeout == 0L) {
            group.shutdownNow()
        } else {
            group.shutdown()
        }
        val wasTerminated: Boolean = group.awaitTermination(timeout, TimeUnit.MILLISECONDS)
        if (!wasTerminated) {
            logger.info("server shutdown timedout")
            group.shutdownNow()
            val wasTerminatedSecondTry: Boolean = group.awaitTermination(500, TimeUnit.MILLISECONDS)
            if (wasTerminatedSecondTry) {
                logger.info("server shutdown succeeded after second try")
            } else {
                logger.info("server shutdown failed, ignoring...")
            }
        }
        logger.info("closing server socket")
        asyncServerSocketChannel.close()
        logger.info("server shutdown")
    }

    fun shutdown() = shutdown(Long.MAX_VALUE)

    fun join() = runBlocking { acceptCoroutine.join() }

    fun exit() {
        shutdown(0)
        join()
    }

    override fun close() {
        shutdown()
        join()
    }

    private val clientContainer = ConnectedClientContainer()

    private val acceptCoroutine: Job = CoroutineScope(multiThreadDispatcher).launch {
        logger.info("listening coroutine started")
        asyncServerSocketChannel.bind(InetSocketAddress(listeningAddress, listeningPort))
        println(Messages.SERVER_IS_BOUND)
        logger.info("server listening on {}:{}", listeningAddress, listeningPort)
        // SupervisorScope is used to prevent the listener coroutine to be canceled when a client coroutine
        // fails or is canceled.
        val exceptionHandler = CoroutineExceptionHandler { _, exception ->
            logger.info("Unhandled exception: {}", exception.message)
        }
        supervisorScope {
            launch(exceptionHandler) {
                acceptLoop(this)
            }
        }
    }

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