package pt.isel.pc.problemsets.set3

import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.set3.base.Messages
import pt.isel.pc.problemsets.set3.base.Server
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import pt.isel.pc.problemsets.utils.TestClient
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

class MessagingTests {

    @Test
    fun `first scenario`() {
        // given: a set of clients
        val nOfClients = 5
        val clients = List(nOfClients) {
            TestClient("client-$it")
        }
        Server("localhost", 8080).use { server ->

            clients.forEach {
                // when: a client connects and requests to enter a room
                it.connect()
                it.send("/enter lounge")
                // then: it receives a success message
                assertEquals(Messages.enteredRoom("lounge"), it.receive())
            }

            // when: client 0 sends a message
            clients[0].send("Hi there.")
            clients.forEach {
                if (it != clients[0]) {
                    // then: all clients, other than client 0, receive the message
                    assertEquals(Messages.messageFromClient("client-1", "Hi there."), it.receive())
                }
            }

            // when: client 1 sends a message
            clients[1].send("Hello.")
            clients.forEach {
                if (it != clients[1]) {
                    // then: all clients, other than client 1, receive the message
                    assertEquals(Messages.messageFromClient("client-2", "Hello."), it.receive())
                }
            }
            clients.forEach {
                // when: all clients ask to exit
                it.send("/exit")
                // then: all clients receive the exit acknowledgment
                assertEquals(Messages.BYE, it.receive())
            }
        }
    }

    /**
     * Stress test where a large number of clients send a large number of messages and we
     * check that each client received all the messages from all other clients.
     */
    @Test
    fun `stress test`() {
        // given:
        val nOfClients = 100
        val nOfMessages = 100
        val delayBetweenMessagesInMillis = 0L

        // and: a set of clients
        val clients = List(nOfClients) {
            TestClient("client-$it")
        }
        val testHelper = MultiThreadTestHelper(120.seconds)
        val counter = ConcurrentHashMap<String, AtomicLong>()
        Server("localhost", 8080).use { server ->
            // when: all clients connect and enter the same room
            clients.forEach {
                it.connect()
                it.send("/enter lounge")
                assertEquals(Messages.enteredRoom("lounge"), it.receive())
            }
            clients.forEach { client ->
                testHelper.createAndStartMultipleThreads(0) { _, _ ->
                    // Helper thread to read all messages sent to a client ...
                    val readThread = testHelper.createAndStartThread {
                        var receivedMessages = 0
                        while (true) {
                            try {
                                val msg = client.receive() ?: break

                                // ... and updated a shared map with an occurrence counter for each message
                                counter.computeIfAbsent(msg) { AtomicLong() }.incrementAndGet()

                                // ... when all the expected messages are received, we end the thread
                                if (++receivedMessages == (nOfClients - 1) * nOfMessages) {
                                    break
                                }
                            } catch (ex: SocketTimeoutException) {
                                throw RuntimeException("timeout with '$receivedMessages' received messages", ex)
                            }
                        }
                    }
                    // and: all the messages are sent, with an optional delay between messages
                    (1..nOfMessages).forEach { index ->
                        client.send("message-$index")
                        if (delayBetweenMessagesInMillis != 0L) {
                            Thread.sleep(delayBetweenMessagesInMillis)
                        }
                    }

                    // and: the reader thread ended, meaning all expected messages were received
                    readThread.join()

                    // and: we ask the client to exit
                    client.send("/exit")
                    assertEquals(Messages.BYE, client.receive())
                }
            }
            testHelper.join()
            // then: Each sent message was received (nOfClients - 1) times.
            (1..nOfClients).forEach {
                val clientId = "client-$it"
                (1..nOfMessages).forEach { index ->
                    val message = Messages.messageFromClient(clientId, "message-$index")
                    val counts = counter[message]
                    assertNotNull(counts, "counter for message '$message' must not be null")
                    assertEquals((nOfClients - 1).toLong(), counts.get())
                }
            }
        }
    }
}