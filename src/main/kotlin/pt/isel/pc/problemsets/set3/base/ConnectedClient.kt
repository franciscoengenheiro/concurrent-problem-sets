package pt.isel.pc.problemsets.set3.base

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import pt.isel.pc.problemsets.line.LineReader
import pt.isel.pc.problemsets.set3.solution.AsyncMessageQueue
import pt.isel.pc.problemsets.set3.solution.readSuspend
import pt.isel.pc.problemsets.set3.solution.writeLine
import java.nio.channels.AsynchronousSocketChannel
import kotlin.time.Duration

/**
 * Responsible for handling a connected client accepted by the server, namely
 * - handling the client requests.
 * - reading lines from the client.
 * - writing lines to the client.
 *
 * The client is identified by a unique id.
 */
class ConnectedClient(
    private val asyncSocketChannel: AsynchronousSocketChannel,
    id: Int,
    private val roomContainer: RoomContainer,
    private val clientContainer: ConnectedClientContainer,
    coroutineScope: CoroutineScope
) {

    private val name: String = "client-$id"
    private val controlQueue: AsyncMessageQueue<ControlMessage> = AsyncMessageQueue(Int.MAX_VALUE)
    private var readLoopCoroutine: Job? = null
    private val mainLoopCoroutine: Job = coroutineScope.launch {
        readLoopCoroutine = launch { readLoop() }
        mainLoop()
    }
    private var room: Room? = null

    // The control messages the main loop handles...
    private sealed interface ControlMessage {
        // ... a message sent by a room
        data class RoomMessage(val sender: ConnectedClient, val message: String) : ControlMessage

        // ... a line sent by the connected remote client
        data class RemoteClientRequest(val request: ClientRequest) : ControlMessage

        // ... the connected remote client closes the socket (local receiver)
        object RemoteInputClosed : ControlMessage {
            override fun toString(): String = "RemoteInputClosed"
        }

        // ... the shutdown method was called
        object Shutdown : ControlMessage {
            override fun toString(): String = "Shutdown"
        }
    }

    /**
     * Sends a message to a connected client by placing it in the control queue.
     * @param sender the client that sent the message.
     * @param message the message to send.
     */
    suspend fun send(sender: ConnectedClient, message: String) {
        controlQueue.enqueue(ControlMessage.RoomMessage(sender, message))
    }

    /**
     * Gracefully shuts down the client coroutines by ending the main loop.
     */
    suspend fun shutdown() {
        logger.info("[{}] received shutdown request by the server", name)
        controlQueue.enqueue(ControlMessage.Shutdown)
    }

    /**
     * Synchronizes with the client shutdown.
     */
    suspend fun join() = mainLoopCoroutine.join()

    /**
     * Reads messages from the control queue and writes them to the client.
     */
    private suspend fun mainLoop() {
        logger.info("[{}] main loop started", name)
        asyncSocketChannel.writeLine(Messages.CLIENT_WELCOME)
        while (true) {
            logger.info("[{}] waiting for message in control queue", name)
            when (val control = controlQueue.dequeue(Duration.INFINITE)) {
                is ControlMessage.Shutdown -> {
                    logger.info("[{}] received control message: {}", name, control)
                    asyncSocketChannel.writeLine(Messages.SERVER_IS_ENDING)
                    break
                }

                is ControlMessage.RoomMessage -> {
                    logger.trace("[{}] received control message: {}", name, control)
                    val message = Messages.messageFromClient(control.sender.name, control.message)
                    asyncSocketChannel.writeLine(message)
                }

                is ControlMessage.RemoteClientRequest -> {
                    val line = control.request
                    if (handleRemoteClientRequest(line, asyncSocketChannel)) {
                        break
                    }
                }

                ControlMessage.RemoteInputClosed -> {
                    logger.info("[{}] received control message: {}", name, control)
                    break
                }
            }
        }
        withContext(NonCancellable) {
            logger.info("[{}] inside main loop cancellation handler", name)
            logger.info("[{}] cancelling read loop", name)
            // the main loop needs to ensure that the read loop is finished before it can finish
            readLoopCoroutine?.cancelAndJoin()
            clientContainer.remove(this@ConnectedClient)
            logger.info("[{}] explicitly closing the socket", name)
            asyncSocketChannel.close()
            logger.info("[{}] main loop ending", name)
        }
    }

    /**
     * Handles a client request.
     * @param clientRequest the client request to handle.
     * @param socketChannel the client socket channel to write to.
     * @return true if the client requested to exit, false otherwise.
     */
    private suspend fun handleRemoteClientRequest(
        clientRequest: ClientRequest,
        socketChannel: AsynchronousSocketChannel
    ): Boolean {
        when (clientRequest) {
            is ClientRequest.EnterRoomCommand -> {
                logger.info("[{}] received remote client request: {}", name, clientRequest)
                room?.remove(this)
                room = roomContainer.getByName(clientRequest.name).also {
                    it.add(this)
                }
                socketChannel.writeLine(Messages.enteredRoom(clientRequest.name))
            }

            ClientRequest.LeaveRoomCommand -> {
                logger.info("[{}] received remote client request: {}", name, clientRequest)
                room?.remove(this)
                room = null
            }

            ClientRequest.ExitCommand -> {
                logger.info("[{}] received remote client request: {}", name, clientRequest)
                room?.remove(this)
                socketChannel.writeLine(Messages.BYE)
                return true
            }

            is ClientRequest.InvalidRequest -> {
                logger.info("[{}] received remote client request: {}", name, clientRequest)
                socketChannel.writeLine(Messages.ERR_INVALID_LINE)
            }

            is ClientRequest.Message -> {
                logger.trace("[{}] received remote client request: {}", name, clientRequest)
                val currentRoom = room
                if (currentRoom != null) {
                    // broadcast the message to all clients in the room
                    currentRoom.post(this, clientRequest.value)
                } else {
                    socketChannel.writeLine(Messages.ERR_NOT_IN_A_ROOM)
                }
            }
        }
        return false
    }

    /**
     * Reads lines from the client and places them in the control queue.
     */
    private suspend fun readLoop() {
        try {
            while (true) {
                logger.info("[{}] waiting for line from client", name)
                val lineReader = LineReader { byteBuffer -> asyncSocketChannel.readSuspend(byteBuffer) }
                val line = lineReader.readLine()
                if (line == null) {
                    logger.info("[{}] end of input stream reached", name)
                    controlQueue.enqueue(ControlMessage.RemoteInputClosed)
                    break
                } else {
                    logger.info("[{}] received line: {}", name, line)
                    controlQueue.enqueue(ControlMessage.RemoteClientRequest(ClientRequest.parse(line)))
                }
            }
        } catch (e: Exception) {
            logger.error("[{}] error reading from client: {}", name, e.message)
            controlQueue.enqueue(ControlMessage.RemoteInputClosed)
        }
        logger.info("[{}] inside read loop cancellation handler", name)
        // read loop is finished, so the main loop should also finish
        mainLoopCoroutine.cancel() // cannot use cancelAndJoin() here, because it would deadlock
        logger.info("[{}] read loop ending", name)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ConnectedClient::class.java)
    }
}