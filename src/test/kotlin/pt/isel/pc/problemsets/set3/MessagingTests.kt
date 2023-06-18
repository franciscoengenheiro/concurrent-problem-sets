package pt.isel.pc.problemsets.set3

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.set3.base.Messages
import pt.isel.pc.problemsets.set3.base.Server
import pt.isel.pc.problemsets.set3.solution.use
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import pt.isel.pc.problemsets.utils.TestClient
import pt.isel.pc.problemsets.utils.randomString
import pt.isel.pc.problemsets.utils.randomTo
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

class MessagingTests {

    private val listeningAddress = "localhost"
    private val listeningPort = 9000

    @Test
    fun `first basic scenario`() {
        // given: a random set of clients
        val nOfClients = 5
        require(nOfClients > 1)
        val clients = List(nOfClients) {
            TestClient(it, listeningAddress, listeningPort)
        }
        runBlocking {
            Server(listeningAddress, listeningPort).use { server ->
                // and: a server listening
                server.waitUntilListening()

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
    }

    @Test
    fun `random basic scenario`() {
        // given: a random set of clients
        val nOfClients = 500 randomTo 1000
        require(nOfClients > 1)
        val clients = List(nOfClients) {
            TestClient(it, listeningAddress, listeningPort)
        }
        runBlocking {
            Server(listeningAddress, listeningPort).use { server ->
                // and: a server listening
                server.waitUntilListening()

                val roomName = randomString(10)

                clients.forEach {
                    // when: a client connects and requests to enter a room
                    it.connect()
                    it.send("/enter $roomName")
                    // then: it receives a success message
                    assertEquals(Messages.enteredRoom(roomName), it.receive())
                }

                val randomClientA = clients.random()
                val messageA = randomString(15)
                // when: client A sends a message
                clients[randomClientA.id].send(messageA)
                clients.forEach {
                    if (it != clients[randomClientA.id]) {
                        val idFromServer = randomClientA.id + 1 // server labels clients from 1 to n
                        // then: all clients, other than client A, receive the message
                        assertEquals(Messages.messageFromClient("client-$idFromServer", messageA), it.receive())
                    }
                }

                val randomClientB = clients.random()
                val messageB = randomString(15)

                // when: client B sends a message
                clients[randomClientB.id].send(messageB)
                clients.forEach {
                    if (it != clients[randomClientB.id]) {
                        val idFromServer = randomClientB.id + 1 // server labels clients from 1 to n
                        // then: all clients, other than client B, receive the message
                        assertEquals(Messages.messageFromClient("client-$idFromServer", messageB), it.receive())
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
    }

    /**
     * Stress test where a large number of clients send a large number of messages and we
     * check that each client received all the messages from all other clients.
     */
    @Test
    fun `stress test`() {
        // given:
        val nOfClients = 4
        require(nOfClients > 0)
        val nOfMessages = 2
        require(nOfMessages > 0)
        val delayBetweenMessagesInMillis = 0L

        // and: a set of clients
        val clients = List(nOfClients) {
            TestClient(it, listeningAddress, listeningPort)
        }
        val testHelper = MultiThreadTestHelper(10.seconds)
        val counter = ConcurrentHashMap<String, AtomicLong>()
        runBlocking {
            Server(listeningAddress, listeningPort).use { server ->

                // and: a server listening
                server.waitUntilListening()

                // when: all clients connect and enter the same room
                clients.forEach {
                    it.connect()
                    it.send("/enter lounge")
                    assertEquals(Messages.enteredRoom("lounge"), it.receive())
                }

                clients.forEach { client ->
                    // and: all the messages are sent, with an optional delay between messages
                    (1..nOfMessages).forEach { index ->
                        client.send("message-$index")
                        if (delayBetweenMessagesInMillis != 0L) {
                            Thread.sleep(delayBetweenMessagesInMillis)
                        }
                    }
                }
                clients.forEach { client ->
                    // Helper thread to read all messages sent to a client...
                    val readThread = testHelper.createAndStartThread {
                        var receivedMessages = 0
                        while (true) {
                            try {
                                val msg = client.receive() ?: break
                                // ... and updated a shared map with an occurrence counter for each message
                                counter.computeIfAbsent(msg) { AtomicLong() }.incrementAndGet()

                                // ... when all the expected messages are received, the thread ends
                                if (++receivedMessages == (nOfClients - 1) * nOfMessages) {
                                    break
                                }
                            } catch (ex: SocketTimeoutException) {
                                throw RuntimeException("Client ${client.id} timed out with $receivedMessages messages received")
                            }
                        }
                    }

                    // and: the reader thread ended, meaning all expected messages were received
                    readThread.join()

                    // and: we ask the client to exit
                    client.send("/exit")
                    assertEquals(Messages.BYE, client.receive())
                }
                testHelper.join() // to catch any exception thrown by the threads
                // then: each sent message was received (nOfClients - 1) times.
                (1..nOfClients).forEach {
                    val clientId = "client-$it"
                    (1..nOfMessages).forEach { index ->
                        val message = Messages.messageFromClient(clientId, "message-$index")
                        val messageCounter = counter[message]
                        assertNotNull(messageCounter, "counter for message '$message' must not be null")
                        assertEquals((nOfClients - 1).toLong(), messageCounter.get())
                    }
                }
            }
        }
    }
}