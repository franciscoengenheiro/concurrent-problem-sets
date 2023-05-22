package pt.isel.pc.problemsets.set3.base

import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Represents a server to which clients can connect, enter and leave rooms, and send messages.
 */
class Server(
    private val listeningAddress: String,
    private val listeningPort: Int,
) : AutoCloseable {

    private val serverSocket: ServerSocket = ServerSocket()
    private val isListening = CountDownLatch(1)

    /**
     * The listening thread is mainly comprised by loop waiting for connections and creating a [ConnectedClient]
     * for each accepted connection.
     */
    private val listeningThread = thread(isDaemon = true) {
        serverSocket.use { serverSocket ->
            serverSocket.bind(InetSocketAddress(listeningAddress, listeningPort))
            logger.info("server socket bound to ({}:{})", listeningAddress, listeningPort)
            println(Messages.SERVER_IS_BOUND)
            isListening.countDown()
            acceptLoop(serverSocket)
        }
    }

    fun waitUntilListening() = isListening.await()

    fun shutdown() {
        // Currently, the only way to unblock the listening thread from the listen method is by closing
        // the server socket.
        logger.info("closing server socket as a way to 'interrupt' the listening thread")
        serverSocket.close()
    }

    fun join() = listeningThread.join()

    override fun close() {
        shutdown()
        join()
    }

    private fun acceptLoop(serverSocket: ServerSocket) {
        var clientId = 0
        val roomContainer = RoomContainer()
        val clientContainer = ConnectedClientContainer()
        while (true) {
            try {
                logger.info("accepting new client")
                val socket = serverSocket.accept()
                logger.info("client socket accepted, remote address is {}", socket.inetAddress.hostAddress)
                println(Messages.SERVER_ACCEPTED_CLIENT)
                val client = ConnectedClient(socket, ++clientId, roomContainer, clientContainer)
                clientContainer.add(client)
            } catch (ex: SocketException) {
                logger.info("SocketException, ending")
                // We assume that an exception means the server was asked to terminate
                break
            }
        }
        clientContainer.shutdown()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(Server::class.java)
    }
}