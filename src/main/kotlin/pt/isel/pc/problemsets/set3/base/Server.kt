package pt.isel.pc.problemsets.set3.base

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import pt.isel.pc.problemsets.set3.solution.acceptSuspend
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousServerSocketChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Represents a server to which clients can connect, enter and leave rooms, and send messages.
 */
class Server(
    private val listeningAddress: String,
    private val listeningPort: Int,
    nThreads: Int = 10
) : AutoCloseable {

    init {
        require(nThreads > 0) { "number of threads must be positive" }
    }

    private val multiThreadExecutor: ExecutorService = Executors.newFixedThreadPool(nThreads)
    private val multiThreadDispatcher: ExecutorCoroutineDispatcher = multiThreadExecutor.asCoroutineDispatcher()
    private val group: AsynchronousChannelGroup = AsynchronousChannelGroup.withThreadPool(multiThreadExecutor)
    private val asyncServerSocketChannel: AsynchronousServerSocketChannel = AsynchronousServerSocketChannel.open(group)

    fun shutdown(timeout: Long) {
        logger.info("cancelling listening coroutine")
        listeningCoroutine.cancel()
        asyncServerSocketChannel.close()
        logger.info("shutting down the server")
        if (timeout == 0L) group.shutdownNow() else group.shutdown()
        val wasTerminated: Boolean = group.awaitTermination(timeout, TimeUnit.MILLISECONDS)
        if (!wasTerminated) {
            logger.info("server shutdown timeout")
            group.shutdownNow()
            // TODO("calling shutdown does not garantee that the group is terminated")
        }
        logger.info("server shutdown")
    }

    fun shutdown() = shutdown(Long.MAX_VALUE)

    fun join() = runBlocking {
        listeningCoroutine.join()
    }

    fun exit() {
        shutdown(0)
        join()
    }

    override fun close() {
        shutdown()
        join()
    }

    private val listeningCoroutine: Job = CoroutineScope(multiThreadDispatcher).launch {
        logger.info("listening coroutine started")
        asyncServerSocketChannel.bind(InetSocketAddress(listeningAddress, listeningPort))
        logger.info("server listening on {}:{}", listeningAddress, listeningPort)
        asyncServerSocketChannel.use {
            logger.info("accepting connections")
            // SupervisorScope is used to avoid the listener coroutine to be canceled when a client coroutine
            // fails or is canceled.
            supervisorScope {
                acceptLoop(asyncServerSocketChannel, this)
            }
        }
    }

    private suspend fun acceptLoop(asyncServerSocket: AsynchronousServerSocketChannel, coroutineScope: CoroutineScope) {
        var clientId = 0
        val roomContainer = RoomContainer()
        val clientContainer = ConnectedClientContainer()
        while(true) {
            logger.info("accepting new client")
            val asyncSocketChannel = asyncServerSocket.acceptSuspend()
            coroutineScope.launch {
                println(Messages.SERVER_ACCEPTED_CLIENT)
                logger.info("client socket accepted, remote address is {}", asyncSocketChannel.remoteAddress)
                val client = ConnectedClient(
                    asyncSocketChannel = asyncSocketChannel,
                    id = ++clientId,
                    roomContainer = roomContainer,
                    clientContainer = clientContainer,
                    coroutineScope = this,
                )
                clientContainer.add(client)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(Server::class.java)
    }
}