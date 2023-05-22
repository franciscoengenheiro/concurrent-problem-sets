package pt.isel.pc.problemsets.set3.base

import java.util.HashSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Represents a room, namely by containing all the clients in the room
 */
class Room(
    private val name: String,
) {

    private val lock = ReentrantLock()
    private val connectedClients = HashSet<ConnectedClient>()

    fun add(connectedClient: ConnectedClient) = lock.withLock {
        connectedClients.add(connectedClient)
    }

    fun remove(connectedClient: ConnectedClient) = lock.withLock {
        connectedClients.remove(connectedClient)
    }

    fun post(sender: ConnectedClient, message: String) = lock.withLock {
        connectedClients.forEach {
            if (it != sender) {
                it.send(sender, message)
            }
        }
    }

    override fun toString() = name
}