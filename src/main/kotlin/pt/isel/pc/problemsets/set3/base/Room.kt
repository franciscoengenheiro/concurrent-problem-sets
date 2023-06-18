package pt.isel.pc.problemsets.set3.base

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Represents a room, namely by containing all the clients in the room.
 */
class Room(
    private val name: String
) {

    private val mutex: Mutex = Mutex()
    private val connectedClients = HashSet<ConnectedClient>()

    /**
     * Adds a client to the room.
     * @param connectedClient the client to add.
     */
    suspend fun add(connectedClient: ConnectedClient) = mutex.withLock {
        connectedClients.add(connectedClient)
    }

    /**
     * Removes a client from the room.
     * @param connectedClient the client to remove.
     */
    suspend fun remove(connectedClient: ConnectedClient) = mutex.withLock {
        connectedClients.remove(connectedClient)
    }

    /**
     * Broadcasts a message to all the clients in the room, except the sender.
     * @param sender the client that sent the message.
     * @param message the message to broadcast.
     */
    suspend fun post(sender: ConnectedClient, message: String) = mutex.withLock {
        connectedClients.forEach {
            if (it != sender) {
                it.send(sender, message)
            }
        }
    }

    override fun toString() = name
}