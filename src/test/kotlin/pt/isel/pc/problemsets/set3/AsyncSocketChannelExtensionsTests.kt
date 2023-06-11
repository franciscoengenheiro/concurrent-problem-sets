package pt.isel.pc.problemsets.set3

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import pt.isel.pc.problemsets.set3.solution.acceptSuspend
import pt.isel.pc.problemsets.set3.solution.readSuspend
import pt.isel.pc.problemsets.set3.solution.writeSuspend
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Note: These tests are not meant to be run in parallel
class AsyncSocketChannelExtensionsTests {

    companion object {
        private val logger = LoggerFactory.getLogger(AsyncSocketChannelExtensionsTests::class.java)
        private val serverSocket: AsynchronousServerSocketChannel = AsynchronousServerSocketChannel.open()
        private val clientSocket: AsynchronousSocketChannel = AsynchronousSocketChannel.open()
        private const val listeningAddress = "localhost"
        private const val listeningPort = 9000

        @BeforeAll
        @JvmStatic
        fun setup() {
            serverSocket.bind(InetSocketAddress(listeningAddress, listeningPort))
            logger.info("Server listening on {}:{}", listeningAddress, listeningPort)
            val future = clientSocket.connect(InetSocketAddress(listeningAddress, listeningPort))
            future.get()
            assertTrue { clientSocket.isOpen }
            logger.info("connected client")
        }
    }

    private val message = "méssage to `sªend/-"

    @Test
    fun `acceptSuspend should accept a connection`() {
        runBlocking {
            launch(Dispatchers.IO) {
                logger.info("Waiting for connection")
                val acceptedSocket = serverSocket.acceptSuspend()
                logger.info("Connection accepted")
                assertTrue { acceptedSocket.isOpen }
                assertNotNull(acceptedSocket.remoteAddress)
            }
        }
    }

    @Test
    fun `readSuspend should read data from the channel`() {
        val buffer = ByteBuffer.allocate(1024)
        runBlocking(Dispatchers.IO) {
            logger.info("Waiting for bytes to write")
            val writeLen = clientSocket.writeSuspend(ByteBuffer.wrap(message.toByteArray()))
            logger.info("Sent $writeLen bytes")
            launch(Dispatchers.IO) {
                logger.info("Waiting for bytes to read")
                val readLen = clientSocket.readSuspend(buffer)
                logger.info("Read $readLen bytes")
                buffer.flip()
                val receivedMessage = String(buffer.array(), 0, readLen)
                logger.info("Received message: $receivedMessage")
                assertEquals(receivedMessage, message)
            }
        }
    }

    @Test
    fun `writeSuspend should write data to the channel`() {
        runBlocking(Dispatchers.IO) {
            logger.info("Waiting for bytes to write")
            val nBytes = clientSocket.writeSuspend(ByteBuffer.wrap(message.toByteArray()))
            logger.info("Sent $nBytes bytes")
            assertEquals(nBytes, message.toByteArray().size)
        }
    }

    @Test
    fun `when readSuspend is canceled, the channel should be closed, if the operation was not completed`() {
        runBlocking {
            val job = launch(Dispatchers.IO) {
                clientSocket.readSuspend(ByteBuffer.allocate(1024))
            }
            assertTrue(clientSocket.isOpen)
            // wait for the read to start
            delay(1000)
            // wait for the job to be canceled
            job.cancelAndJoin()
            assertFalse(clientSocket.isOpen)
        }
    }

    @Test
    fun `when writeSuspend is canceled, the channel should be closed, if the operation was not completed`() {
        runBlocking {
            val job = launch(Dispatchers.IO) {
                clientSocket.writeSuspend(ByteBuffer.wrap(message.toByteArray()))
            }
            assertTrue(clientSocket.isOpen)
            // wait for the job to be canceled
            job.cancelAndJoin()
            assertFalse(clientSocket.isOpen)
        }
    }
}