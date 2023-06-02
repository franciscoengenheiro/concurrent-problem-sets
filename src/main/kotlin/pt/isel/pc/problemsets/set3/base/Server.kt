package pt.isel.pc.problemsets.set3.base

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import pt.isel.pc.problemsets.set3.acceptSuspend
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Represents a server to which clients can connect, enter and leave rooms, and send messages.
 */
class Server(
    private val listeningAddress: String,
    private val listeningPort: Int,
) : AutoCloseable {

    // TODO("is a group necessary in open()?")
    private val asyncServerSocket: AsynchronousServerSocketChannel = AsynchronousServerSocketChannel.open()
    private val isListening = CountDownLatch(1)
    private val multiThreadDispatcher = Executors.newFixedThreadPool(10).asCoroutineDispatcher()
    private val serverScope = CoroutineScope(multiThreadDispatcher + SupervisorJob())

    /**
     * The listening thread is mainly comprised by loop waiting for connections and creating a [ConnectedClient]
     * for each accepted connection.
     */
    private val listeningThread: Thread = thread(isDaemon = true) {
        asyncServerSocket.use { serverSocket ->
            serverSocket.bind(InetSocketAddress(listeningAddress, listeningPort))
            logger.info("server socket bound to ({}:{})", listeningAddress, listeningPort)
            println(Messages.SERVER_IS_BOUND)
            isListening.countDown()
            // This runBlocking is necessary to avoid the thread from ending
            runBlocking(serverScope.coroutineContext) {
                acceptLoop(serverSocket)
            }
        }
    }

    fun waitUntilListening() = isListening.await()

    fun shutdown() {
        // Currently, the only way to unblock the listening thread from the listen method is by closing
        // the server socket.
        logger.info("server is in shutdown mode")
        logger.info("canceling all launched coroutines within the server scope before closing the server socket")
        serverScope.cancel()
        asyncServerSocket.close()
    }

    fun join() = listeningThread.join()

    override fun close() {
        shutdown()
        join()
    }

    private suspend fun acceptLoop(asyncServerSocket: AsynchronousServerSocketChannel) {
        val clientId = AtomicInteger(0)
        val roomContainer = RoomContainer()
        val clientContainer = ConnectedClientContainer()
        try {
            while(true) {
                logger.info("accepting new client")
                lateinit var asyncSocketChannel: AsynchronousSocketChannel
                try {
                    // Suspends until a connection is received and returns the socket channel for that connection
                    asyncSocketChannel = asyncServerSocket.acceptSuspend()
                } catch (ex: CancellationException) {
                    // accept was canceled, retry
                    continue
                }
                val observedSocketChannel = asyncSocketChannel
                serverScope.launch {
                    println(Messages.SERVER_ACCEPTED_CLIENT)
                    // TODO("Does connect needs to be suspended?")
                    // asyncSocketChannel.connect(InetSocketAddress(listeningAddress, listeningPort))
                    logger.info("client socket accepted, remote address is {}", observedSocketChannel.remoteAddress)
                    val client = ConnectedClient(
                        asyncSocketChannel = observedSocketChannel,
                        id = clientId.incrementAndGet(),
                        roomContainer = roomContainer,
                        clientContainer = clientContainer,
                        serverScope = serverScope
                    )
                    clientContainer.add(client)
                }
            }
        } catch (ex: SocketException) {
            logger.info("SocketException, ending")
            // We assume that an exception means the server was asked to terminate
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(Server::class.java)
    }
}