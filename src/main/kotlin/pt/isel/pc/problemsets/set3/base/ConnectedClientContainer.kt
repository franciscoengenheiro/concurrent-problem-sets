package pt.isel.pc.problemsets.set3.base

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Container of all the active clients.
 */
class ConnectedClientContainer {

    private val lock = Mutex()
    private val clients = HashSet<ConnectedClient>()
    private var isShuttingDown: Boolean = false

    /**
     * Adds a client to the container.
     * @param connectedClient the client to add.
     * @throws IllegalStateException if the container is shutting down.
     */
    suspend fun add(connectedClient: ConnectedClient) = lock.withLock {
        check(!isShuttingDown) { "cannot add clients after shutdown" }
        clients.add(connectedClient)
    }

    /**
     * Removes a client from the container.
     * @param connectedClient the client to remove.
     */
    suspend fun remove(connectedClient: ConnectedClient) = lock.withLock {
        clients.remove(connectedClient)
    }

    /**
     * Gracefully shuts down all the clients.
     */
    suspend fun shutdown() {
        val clientList = lock.withLock {
            isShuttingDown = true
            clients.toList()
        }
        // logic outside the lock
        clientList.forEach {
            it.shutdown()
        }
        clientList.forEach {
            it.join()
        }
    }
}