package pt.isel.pc.problemsets.set3

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.set3.base.Messages
import pt.isel.pc.problemsets.set3.base.Server
import pt.isel.pc.problemsets.set3.solution.use
import pt.isel.pc.problemsets.utils.TestClient
import pt.isel.pc.problemsets.utils.TestServer
import pt.isel.pc.problemsets.utils.randomTo
import kotlin.test.assertEquals

internal class ShutdownTests {

    private val listeningAddress = "localhost"
    private val listeningPort = 10000

    @Test
    fun `Abruptly shutdown the server`() {
        // given: a random set of clients
        val nOfClients = 1000 randomTo 2000
        val clients = List(nOfClients) {
            TestClient(it, listeningAddress, listeningPort)
        }
        runBlocking {
            Server(listeningAddress, listeningPort, nrThreads = 10).use { server ->
                // and: a server listening
                server.waitUntilListening()
                coroutineScope {
                    launch {
                        clients.forEach {
                            // when: a client connects
                            it.connect()
                        }
                    }
                }
                // when: sending a signal for the server to end abruptly
                server.exit()
            }
        }
    }

    @Test
    fun `Graceful server shutdown`() {
        // given: a random set of clients
        val nOfClients = 2500 randomTo 5000
        val clients = List(nOfClients) {
            TestClient(it, listeningAddress, listeningPort)
        }
        runBlocking {
            Server(listeningAddress, listeningPort, nrThreads = 10).use { server ->
                // and: a server listening
                server.waitUntilListening()
                coroutineScope {
                    launch {
                        clients.forEach {
                            // when: a client connects
                            it.connect()
                        }
                    }
                }
                clients.forEach {
                    launch {
                        // then: all clients receive the server-ending message
                        assertEquals(Messages.SERVER_IS_ENDING, it.receive())
                    }
                }
                // when: sending a signal for the server to gracefully
                server.shutdown()
            }
        }
    }

    // @Test
    fun `Graceful server shutdown with external process termination`() {
        TestServer.start().use { server ->
            // given: a server listening for connections
            server.waitFor { it == Messages.SERVER_IS_BOUND }
            // and: two connected clients
            val client0 = TestClient(0, listeningAddress, listeningPort).apply { connect() }
            server.waitFor { it == Messages.SERVER_ACCEPTED_CLIENT }

            val client1 = TestClient(1, listeningAddress, listeningPort).apply { connect() }
            server.waitFor { it == Messages.SERVER_ACCEPTED_CLIENT }

            // when: sending a message while not in a room
            client0.send("hello")
            // then: receives expected response
            assertEquals(Messages.ERR_NOT_IN_A_ROOM, client0.receive())

            // when: sending a message to enter a room
            client0.send("/enter lounge")
            // then: receives expected response
            assertEquals(Messages.enteredRoom("lounge"), client0.receive())

            // when: sending a signal for the server to end
            server.sendSignal()

            // then: both clients receive the server-ending message
            assertEquals(Messages.SERVER_IS_ENDING, client0.receive())
            assertEquals(Messages.SERVER_IS_ENDING, client1.receive())
            server.join()
        }
    }
}