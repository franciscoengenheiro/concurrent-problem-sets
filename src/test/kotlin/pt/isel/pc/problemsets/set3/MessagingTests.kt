package pt.isel.pc.problemsets.set3

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.jupiter.api.RepeatedTest
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

    private fun indexedMessage(index: Int): String = "message-$index"

    @Test
    fun `first basic scenario`() {
        // given: a random set of clients
        val nOfClients = 5
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
    fun `random basic scenario with multiple threads`() {
        // given: a random set of clients
        val nOfClients = 500 randomTo 1000
        require(nOfClients > 1)
        val clients = List(nOfClients) {
            TestClient(it, listeningAddress, listeningPort)
        }
        runBlocking {
            Server(listeningAddress, listeningPort, nrThreads = 10).use { server ->
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
                val messageB = randomString(25)

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

    @RepeatedTest(3)
    fun `A random number of clients send a random number of messages with a random delay between messages`() {
        // given:
        val nOfClients = 15 randomTo 20
        require(nOfClients > 0)
        val nOfMessages = 10 randomTo 20
        require(nOfMessages > 0)
        val delayBetweenMessagesInMillis = 0L randomTo 100L
        require(delayBetweenMessagesInMillis >= 0L)

        val testHelper = MultiThreadTestHelper(60.seconds)

        // and: a set of clients
        val clients = List(nOfClients) {
            TestClient(it, listeningAddress, listeningPort)
        }
        val counter = ConcurrentHashMap<String, AtomicLong>()
        runBlocking {
            Server(listeningAddress, listeningPort, nrThreads = 1).use { server ->

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
                        client.send(indexedMessage(index))
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
                        val message = Messages.messageFromClient(clientId, indexedMessage(index))
                        val messageCounter = counter[message]
                        assertNotNull(messageCounter, "counter for message '$message' must not be null")
                        assertEquals((nOfClients - 1).toLong(), messageCounter.get())
                    }
                }
            }
        }
    }

    @Test // TODO("should be randomized")
    fun `Multiple clients send multiple random messages concurrently`() {
        // given:
        val nOfClients = 2
        require(nOfClients > 0)
        val nOfMessages = 20
        require(nOfMessages > 0)
        val delayBetweenMessagesInMillis = 0L
        require(delayBetweenMessagesInMillis >= 0L)

        // and: a set of clients
        val clients = List(nOfClients) {
            TestClient(it, listeningAddress, listeningPort)
        }
        val counter = ConcurrentHashMap<String, AtomicLong>()
        runBlocking(multiThreadDispatcher) {
            Server(listeningAddress, listeningPort, nrThreads = 10).use { server ->

                // and: a server listening
                server.waitUntilListening()

                // when: all clients connect and enter the same room
                coroutineScope {
                    clients.forEach { client ->
                        launch {
                            client.connect()
                            client.send("/enter lounge")
                            assertEquals(Messages.enteredRoom("lounge"), client.receive())
                        }
                    }
                }

                coroutineScope {
                    clients.forEach { client ->
                        launch {
                            // and: all the messages are sent, with an optional delay between messages
                            (1..nOfMessages).forEach { index ->
                                client.send(indexedMessage(index))
                                if (delayBetweenMessagesInMillis != 0L) {
                                    delay(delayBetweenMessagesInMillis)
                                }
                            }
                        }
                    }
                }

                coroutineScope {
                    clients.forEach { client ->
                        launch {
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
                            // and: the reader thread ended, meaning all expected messages were received,
                            // we ask the client to exit
                            client.send("/exit")
                            assertEquals(Messages.BYE, client.receive())
                        }
                    }
                }

                // then: each sent message was received (nOfClients - 1) times.
                (1..nOfClients).forEach {
                    val clientId = "client-$it"
                    (1..nOfMessages).forEach { index ->
                        val message = Messages.messageFromClient(clientId, indexedMessage(index))
                        val messageCounter = counter[message]
                        assertNotNull(messageCounter, "counter for message '$message' must not be null")
                        assertEquals((nOfClients - 1).toLong(), messageCounter.get())
                    }
                }
            }
        }
    }

    companion object {
        @OptIn(DelicateCoroutinesApi::class)
        val multiThreadDispatcher = newFixedThreadPoolContext(10, "multi-thread dispatcher")
    }
}