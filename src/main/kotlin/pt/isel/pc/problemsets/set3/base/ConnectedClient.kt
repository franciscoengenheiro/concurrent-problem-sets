package pt.isel.pc.problemsets.set3.base

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import pt.isel.pc.problemsets.line.LineReader
import pt.isel.pc.problemsets.set3.readSuspend
import pt.isel.pc.problemsets.set3.writeSuspend
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
    private val serverScope: CoroutineScope
) {

    private val name = "client-$id"
    // TODO("change to AsyncMessageQueue here later")
    private val controlQueue = LinkedBlockingQueue<ControlMessage>()
    // TODO("this threads should be removed later")
    private val readLoopThread = thread(isDaemon = true) { readLoop() }
    private val mainLoopThread = thread(isDaemon = true) { mainLoop() }
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

    fun send(sender: ConnectedClient, message: String) {
        // just add a control message into the control queue
        controlQueue.put(ControlMessage.RoomMessage(sender, message))
    }

    fun shutdown() {
        // just add a control message into the control queue
        controlQueue.put(ControlMessage.Shutdown)
    }

    fun join() = mainLoopThread.join()

    private fun mainLoop() {
        logger.info("[{}] main loop started", name)
        asyncSocketChannel.use {
            serverScope.launch {
                try {
                    it.writeSuspend(toByteBuffer(Messages.CLIENT_WELCOME))
                    while (true) {
                        // Blocks the thread executing this loop when trying to dequeue a message from the queue
                        when (val control = controlQueue.poll(Long.MAX_VALUE, TimeUnit.SECONDS)) {
                            is ControlMessage.Shutdown -> {
                                logger.info("[{}] received control message: {}", name, control)
                                it.writeSuspend(toByteBuffer(Messages.SERVER_IS_ENDING))
                                readLoopThread.interrupt()
                                break
                            }

                            is ControlMessage.RoomMessage -> {
                                logger.trace("[{}] received control message: {}", name, control)
                                val message = Messages.messageFromClient(control.sender.name, control.message)
                                it.writeSuspend(toByteBuffer(message))
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
                    }
                } catch (ex: Throwable) {
                    logger.error("[{}] exception in main loop", name, ex)
                }
            }
        }
        readLoopThread.join()
        clientContainer.remove(this)
        logger.info("[{}] main loop ending", name)
    }

    private suspend fun handleRemoteClientRequest(
        clientRequest: ClientRequest,
        asyncSocketChannel: AsynchronousSocketChannel,
    ): Boolean {
        when (clientRequest) {
            is ClientRequest.EnterRoomCommand -> {
                logger.info("[{}] received remote client request: {}", name, clientRequest)
                room?.remove(this)
                room = roomContainer.getByName(clientRequest.name).also {
                    it.add(this)
                }
                asyncSocketChannel.writeSuspend(toByteBuffer(Messages.enteredRoom(clientRequest.name)))
            }

            ClientRequest.LeaveRoomCommand -> {
                logger.info("[{}] received remote client request: {}", name, clientRequest)
                room?.remove(this)
                room = null
            }

            ClientRequest.ExitCommand -> {
                logger.info("[{}] received remote client request: {}", name, clientRequest)
                room?.remove(this)
                asyncSocketChannel.writeSuspend(toByteBuffer(Messages.BYE))
                readLoopThread.interrupt()
                return true
            }

            is ClientRequest.InvalidRequest -> {
                logger.info("[{}] received remote client request: {}", name, clientRequest)
                asyncSocketChannel.writeSuspend(toByteBuffer(Messages.ERR_INVALID_LINE))
            }

            is ClientRequest.Message -> {
                logger.trace("[{}] received remote client request: {}", name, clientRequest)
                val currentRoom = room
                if (currentRoom != null) {
                    currentRoom.post(this, clientRequest.value)
                } else {
                    asyncSocketChannel.writeSuspend(toByteBuffer(Messages.ERR_NOT_IN_A_ROOM))
                }
            }
        }
        return false
    }

    private fun readLoop() {
        asyncSocketChannel.use {
            // TODO("should be inside cycle?")
            val lineReader = LineReader { byteBuffer -> it.readSuspend(byteBuffer) }
            serverScope.launch {
                try {
                    while (true) {
                        val line = lineReader.readLine()
                        if (line == null) {
                            logger.info("[{}] end of input stream reached", name)
                            controlQueue.put(ControlMessage.RemoteInputClosed)
                            cancel()
                        } else {
                            logger.info("[{}] received line: {}", name, line)
                            controlQueue.put(ControlMessage.RemoteClientRequest(ClientRequest.parse(line)))
                        }
                    }
                } catch (ex: Throwable) {
                    logger.info("[{}] Exception on read loop: {}, {}", name, ex.javaClass.name, ex.message)
                    controlQueue.put(ControlMessage.RemoteInputClosed)
                    cancel()
                }
            }
            logger.info("[{}] client loop ending", name)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ConnectedClient::class.java)

        private fun toByteBuffer(message: String): ByteBuffer {
            val bytes = (message + System.lineSeparator()).toByteArray()
            // TODO("where to flush?")
            val buffer = ByteBuffer.wrap(bytes)
            buffer.flip()
            return buffer
        }

    }
}