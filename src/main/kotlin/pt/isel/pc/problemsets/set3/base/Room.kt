package pt.isel.pc.problemsets.set3.base

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Represents a room, namely by containing all the clients in the room
 */
class Room(
    private val name: String
) {

    private val mutex: Mutex = Mutex()
    private val connectedClients = HashSet<ConnectedClient>()

    suspend fun add(connectedClient: ConnectedClient) = mutex.withLock {
        connectedClients.add(connectedClient)
    }

    suspend fun remove(connectedClient: ConnectedClient) = mutex.withLock {
        connectedClients.remove(connectedClient)
    }

    suspend fun post(sender: ConnectedClient, message: String) = mutex.withLock {
        connectedClients.forEach {
            if (it != sender) {
                it.send(sender, message)
            }
        }
    }

    override fun toString() = name
}