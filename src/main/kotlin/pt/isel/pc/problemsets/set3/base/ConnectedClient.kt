package pt.isel.pc.problemsets.set3.base

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import pt.isel.pc.problemsets.line.LineReader
import pt.isel.pc.problemsets.set3.solution.AsyncMessageQueue
import pt.isel.pc.problemsets.set3.solution.readSuspend
import pt.isel.pc.problemsets.set3.solution.writeLine
import pt.isel.pc.problemsets.set3.solution.writeSuspend
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import kotlin.time.Duration

/**
 * Responsible for handling a single-connected client. It is comprised by two threads:
 * - `readLoopThread` - responsible for (blocking) reading lines from the client socket. It is the only thread that
 *    reads from the client socket.
 * - `mainLoopThread` - responsible for handling control messages sent from the outside
 *    or from the inner `readLoopThread`. It is the only thread that writes to the client socket.
 */
class ConnectedClient(
    private val asyncSocketChannel: AsynchronousSocketChannel,
    id: Int,
    private val roomContainer: RoomContainer,
    private val clientContainer: ConnectedClientContainer,
    coroutineScope: CoroutineScope
) {

    private val name: String = "client-$id"
    private val controlQueue: AsyncMessageQueue<ControlMessage> = AsyncMessageQueue(100)
    private var readLoopCoroutine: Job? = null
    private val mainLoopCoroutine: Job = coroutineScope.launch {
        launch { mainLoop() }
        readLoopCoroutine = launch { readLoop() }
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

    suspend fun send(sender: ConnectedClient, message: String) {
        // just add a control message into the control queue
        controlQueue.enqueue(ControlMessage.RoomMessage(sender, message))
    }

    suspend fun shutdown() {
        // just add a control message into the control queue
        controlQueue.enqueue(ControlMessage.Shutdown)
    }

    suspend fun join() = mainLoopCoroutine.join()

    private suspend fun mainLoop() {
        logger.info("[{}] main loop started", name)
        asyncSocketChannel.use {
            try {
                it.writeLine(Messages.CLIENT_WELCOME)
                it.writeLine(Messages.AVAILABLE_COMMANDS)
                while (true) {
                    logger.info("[{}] waiting for message in control queue", name)
                    // Blocks the thread executing this loop when trying to dequeue a message from the queue
                    when (val control = controlQueue.dequeue(Duration.INFINITE)) {
                        is ControlMessage.Shutdown -> {
                            logger.info("[{}] received control message: {}", name, control)
                            it.writeLine(Messages.SERVER_IS_ENDING)
                            readLoopCoroutine?.cancel()
                            break
                        }

                        is ControlMessage.RoomMessage -> {
                            logger.trace("[{}] received control message: {}", name, control)
                            val message = Messages.messageFromClient(control.sender.name, control.message)
                            it.writeLine(message)
                        }

                        is ControlMessage.RemoteClientRequest -> {
                            val line = control.request
                            if (handleRemoteClientRequest(line, it))
                                break
                        }

                        ControlMessage.RemoteInputClosed -> {
                            logger.info("[{}] received control message: {}", name, control)
                            break
                        }
                    }
                    it.writeLine(Messages.lineTerminator)
                }
            } catch (ex: Throwable) {
                logger.error("[{}] exception in main loop", name, ex)
            }
        }
        readLoopCoroutine?.join()
        clientContainer.remove(this)
        logger.info("[{}] main loop ending", name)
    }

    private suspend fun handleRemoteClientRequest(
        clientRequest: ClientRequest,
        socketChannel: AsynchronousSocketChannel,
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
                readLoopCoroutine?.cancel()
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
                    currentRoom.post(this, clientRequest.value)
                } else {
                    socketChannel.writeLine(Messages.ERR_NOT_IN_A_ROOM)
                }
            }
        }
        return false
    }

    private suspend fun readLoop() {
        asyncSocketChannel.use {
            try {
                while (true) {
                    logger.info("[{}] waiting for line from client", name)
                    val lineReader = LineReader { byteBuffer -> it.readSuspend(byteBuffer) }
                    val line = lineReader.readLine()
                    if (line == null) {
                        logger.info("[{}] end of input stream reached", name)
                        controlQueue.enqueue(ControlMessage.RemoteInputClosed)
                        logger.info("canceling read loop coroutine.")
                        readLoopCoroutine?.cancel()
                    } else {
                        logger.info("[{}] received line: {}", name, line)
                        controlQueue.enqueue(ControlMessage.RemoteClientRequest(ClientRequest.parse(line)))
                    }
                }
            } catch (ex: Throwable) {
                logger.info("[{}] Exception on read loop: {}, {}", name, ex.javaClass.name, ex.message)
                controlQueue.enqueue(ControlMessage.RemoteInputClosed)
                logger.info("canceling read loop coroutine.")
                readLoopCoroutine?.cancel()
            }
            logger.info("[{}] client loop ending", name)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ConnectedClient::class.java)
    }
}