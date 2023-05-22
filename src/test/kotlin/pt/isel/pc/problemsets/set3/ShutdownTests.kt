package pt.isel.pc.problemsets.set3

import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.set3.base.Messages
import pt.isel.pc.problemsets.utils.TestClient
import pt.isel.pc.problemsets.utils.TestServer
import kotlin.test.assertEquals

class ShutdownTests {

    @Test
    fun `can shutdown server orderly`() {
        TestServer.start().use { server ->
            // given: a server listening for connections
            server.waitFor { it == Messages.SERVER_IS_BOUND }
            // and: two connected clients
            val client0 = TestClient("client-0").apply { connect() }
            val client1 = TestClient("client-1").apply { connect() }

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