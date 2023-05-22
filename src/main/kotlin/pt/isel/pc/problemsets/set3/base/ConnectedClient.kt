package pt.isel.pc.problemsets.set3.base

import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.net.Socket
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
    private val socket: Socket,
    id: Int,
    private val roomContainer: RoomContainer,
    private val clientContainer: ConnectedClientContainer,
) {

    private val name = "client-$id"

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

    private val controlQueue = LinkedBlockingQueue<ControlMessage>()
    private val readLoopThread = thread(isDaemon = true) { readLoop() }
    private val mainLoopThread = thread(isDaemon = true) { mainLoop() }

    private var room: Room? = null

    private fun mainLoop() {
        logger.info("[{}] main loop started", name)
        socket.use {
            socket.getOutputStream().bufferedWriter().use { writer ->
                writer.writeLine(Messages.CLIENT_WELCOME)
                while (true) {
                    when (val control = controlQueue.poll(Long.MAX_VALUE, TimeUnit.SECONDS)) {
                        is ControlMessage.Shutdown -> {
                            logger.info("[{}] received control message: {}", name, control)
                            writer.writeLine(Messages.SERVER_IS_ENDING)
                            readLoopThread.interrupt()
                            break
                        }

                        is ControlMessage.RoomMessage -> {
                            logger.trace("[{}] received control message: {}", name, control)
                            writer.writeLine(Messages.messageFromClient(control.sender.name, control.message))
                        }

                        is ControlMessage.RemoteClientRequest -> {
                            val line = control.request
                            if (handleRemoteClientRequest(line, writer)) break
                        }

                        ControlMessage.RemoteInputClosed -> {
                            logger.info("[{}] received control message: {}", name, control)
                            break
                        }
                    }
                }
            }
        }
        readLoopThread.join()
        clientContainer.remove(this)
        logger.info("[{}] main loop ending", name)
    }

    private fun handleRemoteClientRequest(
        clientRequest: ClientRequest,
        writer: BufferedWriter,
    ): Boolean {
        when (clientRequest) {
            is ClientRequest.EnterRoomCommand -> {
                logger.info("[{}] received remote client request: {}", name, clientRequest)
                room?.remove(this)
                room = roomContainer.getByName(clientRequest.name).also {
                    it.add(this)
                }
                writer.writeLine(Messages.enteredRoom(clientRequest.name))
            }

            ClientRequest.LeaveRoomCommand -> {
                logger.info("[{}] received remote client request: {}", name, clientRequest)
                room?.remove(this)
                room = null
            }

            ClientRequest.ExitCommand -> {
                logger.info("[{}] received remote client request: {}", name, clientRequest)
                room?.remove(this)
                writer.writeLine(Messages.BYE)
                readLoopThread.interrupt()
                return true
            }

            is ClientRequest.InvalidRequest -> {
                logger.info("[{}] received remote client request: {}", name, clientRequest)
                writer.writeLine(Messages.ERR_INVALID_LINE)
            }

            is ClientRequest.Message -> {
                logger.trace("[{}] received remote client request: {}", name, clientRequest)
                val currentRoom = room
                if (currentRoom != null) {
                    currentRoom.post(this, clientRequest.value)
                } else {
                    writer.writeLine(Messages.ERR_NOT_IN_A_ROOM)
                }
            }
        }
        return false
    }

    private fun readLoop() {
        socket.getInputStream().bufferedReader().use { reader ->
            try {
                while (true) {
                    val line: String? = reader.readLine()
                    if (line == null) {
                        logger.info("[{}] end of input stream reached", name)
                        controlQueue.put(ControlMessage.RemoteInputClosed)
                        return
                    }
                    controlQueue.put(ControlMessage.RemoteClientRequest(ClientRequest.parse(line)))
                }
            } catch (ex: Throwable) {
                logger.info("[{}] Exception on read loop: {}, {}", name, ex.javaClass.name, ex.message)
                // clear interrupt flag
                Thread.interrupted()
                controlQueue.put(ControlMessage.RemoteInputClosed)
            }
            logger.info("[{}] client loop ending", name)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ConnectedClient::class.java)

        private fun BufferedWriter.writeLine(msg: String) {
            this.write(msg)
            this.newLine()
            this.flush()
        }
    }
}