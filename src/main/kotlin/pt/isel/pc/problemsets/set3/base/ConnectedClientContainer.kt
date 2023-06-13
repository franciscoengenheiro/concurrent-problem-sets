package pt.isel.pc.problemsets.set3.base

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Container of all the active clients
 */
class ConnectedClientContainer {

    private val lock = Mutex()
    private val clients = HashSet<ConnectedClient>()
    private var isShuttingDown: Boolean = false

    suspend fun add(connectedClient: ConnectedClient) = lock.withLock {
        check(!isShuttingDown) { "cannot add clients after shutdown" }
        clients.add(connectedClient)
    }

    suspend fun remove(connectedClient: ConnectedClient) = lock.withLock {
        clients.remove(connectedClient)
    }

    suspend fun shutdown() {
        val clientList = lock.withLock {
            isShuttingDown = true
            clients.toList()
        }
        clientList.forEach {
            it.shutdown()
        }
        clientList.forEach {
            it.join()
        }
    }
}