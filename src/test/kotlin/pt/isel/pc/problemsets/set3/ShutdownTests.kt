package pt.isel.pc.chat

import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.set3.base.Messages
import pt.isel.pc.problemsets.utils.TestClient
import pt.isel.pc.problemsets.utils.TestServer
import kotlin.test.assertEquals

class ShutdownTests {

    // Has to equal to the server log bind settings of the server in the application
    // otherwise, the project has to be recompiled
    private val listeningAddress = "localhost"
    private val listeningPort = 8000

    @Test
    fun `can shutdown server orderly`() {
        TestServer.start().use { server ->
            // given: a server listening for connections
            server.waitFor { it == Messages.SERVER_IS_BOUND }
            // and: two connected clients
            val client0 = TestClient(0, listeningAddress, listeningPort).apply { connect() }
            val client1 = TestClient(1, listeningAddress, listeningPort).apply { connect() }

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