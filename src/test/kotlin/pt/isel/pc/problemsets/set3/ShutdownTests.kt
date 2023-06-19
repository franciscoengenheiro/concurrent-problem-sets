package pt.isel.pc.problemsets.set3

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.set3.base.Messages
import pt.isel.pc.problemsets.set3.base.Server
import pt.isel.pc.problemsets.set3.solution.use
import pt.isel.pc.problemsets.utils.TestClient
import pt.isel.pc.problemsets.utils.TestServer
import pt.isel.pc.problemsets.utils.randomString
import pt.isel.pc.problemsets.utils.randomTo
import kotlin.test.assertEquals

class ShutdownTests {

    // Has to equal to the server bind settings in the application entry point
    // otherwise, the project has to be recompiled
    private val listeningAddress = "localhost"
    private val listeningPort = 8000

    @Test
    fun `Graceful server shutdown`() {
        // given: a random set of clients
        val nOfClients = 25 randomTo 50
        require(nOfClients > 1)
        val clients = List(nOfClients) {
            TestClient(it, listeningAddress, listeningPort)
        }
        runBlocking {
            Server(listeningAddress, listeningPort, nrThreads = 1).use { server ->
                // and: a server listening
                server.waitUntilListening()

                clients.forEach {
                    // when: a client connects and requests to enter a room
                    it.connect()
                    it.send(randomString(10))
                    assertEquals(Messages.ERR_NOT_IN_A_ROOM, it.receive())
                }

                launch {
                    clients.forEach {
                        assertEquals(Messages.SERVER_IS_ENDING, it.receive())
                    }
                }

                server.shutdown()
            }
        }
    }

    @Test
    fun `Graceful server shutdown with process termination`() {
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