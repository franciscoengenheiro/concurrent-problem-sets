package pt.isel.pc.problemsets.set3

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

internal class MessagingTests {

    private val listeningAddress = "localhost"
    private val listeningPort = 10000

    private fun indexedMessage(index: Int): String = "message-$index"

    @Test
    fun `First basic messaging scenario single threaded`() {
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
                server.close()
            }
        }
        clients.forEach { it.close() }
    }

    @RepeatedTest(5)
    fun `Basic scenario with a random number of clients and multiple threads`() {
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

                coroutineScope {
                    clients.forEach {
                        launch {
                            // when: a client connects and requests to enter a room
                            it.connect()
                            it.send("/enter $roomName")
                            // then: it receives a success message
                            assertEquals(Messages.enteredRoom(roomName), it.receive())
                        }
                    }
                }

                coroutineScope {
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
                }

                coroutineScope {
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
                }

                coroutineScope {
                    launch {
                        clients.forEach {
                            // when: all clients ask to exit
                            it.send("/exit")
                            // then: all clients receive the exit acknowledgment
                            assertEquals(Messages.BYE, it.receive())
                        }
                    }
                }
            }
        }
        clients.forEach { it.close() }
    }

    @RepeatedTest(5)
    fun `Clients receive error message when sending messages outside of a room`() {
        // given: a random set of clients
        val nOfClients = 1000 randomTo 2000
        require(nOfClients > 1)
        val clients = List(nOfClients) {
            TestClient(it, listeningAddress, listeningPort)
        }
        runBlocking {
            Server(listeningAddress, listeningPort, nrThreads = 1).use { server ->
                // and: a server listening
                server.waitUntilListening()

                coroutineScope {
                    launch {
                        clients.forEach {
                            // when: a client connects
                            it.connect()
                            // and: sends a message while not in a room
                            it.send(randomString(10))
                            // then: receives expected error response
                            assertEquals(Messages.ERR_NOT_IN_A_ROOM, it.receive())
                        }
                    }
                }
            }
        }
        clients.forEach { it.close() }
    }

    @Test
    fun `Clients in different rooms receive messages only within their room`() {
        // given: a random number of rooms
        val nOfRooms = 25 randomTo 50
        require(nOfRooms > 0)
        // and: a random number of clients (at least one per room)
        val nOfClients = 100 randomTo 250
        require(nOfClients > nOfRooms)

        val rooms = List(nOfRooms) { "room-$it" }
        val clients = List(nOfClients) { TestClient(it + 1, listeningAddress, listeningPort) }

        runBlocking {
            Server(listeningAddress, listeningPort, nrThreads = 10).use { server ->
                // and: a server listening
                server.waitUntilListening()

                clients.forEachIndexed { index, client ->
                    // when: each client connects, enters a room, and sends a message
                    client.connect()
                    val roomName = rooms[index % nOfRooms] // distribute clients evenly across rooms
                    client.send("/enter $roomName")
                    assertEquals(Messages.enteredRoom(roomName), client.receive())
                }

                // and: all clients send a message
                clients.forEachIndexed { index, client ->
                    val roomName = rooms[index % nOfRooms]
                    client.send("Hello from client-${client.id} in $roomName")
                    delay(100) // wait a bit to avoid messages being sent out of order
                }

                // then: messages are received only within each respective room
                rooms.forEach { roomName ->
                    // retrieve clients in assigned to this room
                    val clientsInRoom = clients.filterIndexed { index, _ ->
                        rooms[index % nOfRooms] == roomName
                    }
                    // check that each client in the room received the message sent by the other clients in the same room
                    clientsInRoom.forEach { client ->
                        val expectedMessage = "Hello from client-${client.id} in $roomName"
                        clientsInRoom
                            .filter { it != client } // don't check the message sent by the client itself
                            .forEach { otherClientInSameRoom ->
                                assertEquals(
                                    Messages.messageFromClient("client-${client.id}", expectedMessage),
                                    otherClientInSameRoom.receive()
                                )
                            }
                    }
                }

                // and: all clients ask to exit
                clients.forEach { client ->
                    client.send("/exit")
                    assertEquals(Messages.BYE, client.receive())
                }
            }
        }
        clients.forEach { it.close() }
    }

    @RepeatedTest(5)
    fun `Multiple clients send a random number of messages with a random delay between them`() {
        // given:
        val nOfClients = 15 randomTo 25
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
                                throw RuntimeException(
                                    "Client ${client.id} timed out with $receivedMessages messages received"
                                )
                            }
                        }
                    }

                    // and: the reader thread ended, meaning all expected messages were received
                    readThread.join()

                    // and: we ask the client to exit
                    client.send("/exit")
                    assertEquals(Messages.BYE, client.receive())
                }
                // to catch any exception thrown by the threads
                testHelper.join()
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
        clients.forEach { it.close() }
    }
}