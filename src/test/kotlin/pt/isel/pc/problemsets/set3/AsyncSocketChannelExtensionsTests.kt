package pt.isel.pc.problemsets.set3

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import pt.isel.pc.problemsets.async.SuspendableCountDownLatch
import pt.isel.pc.problemsets.set3.solution.acceptSuspend
import pt.isel.pc.problemsets.set3.solution.readSuspend
import pt.isel.pc.problemsets.set3.solution.writeSuspend
import pt.isel.pc.problemsets.utils.randomString
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class AsyncSocketChannelExtensionsTests {

    companion object {
        private val logger = LoggerFactory.getLogger(AsyncSocketChannelExtensionsTests::class.java)
        private const val listeningAddress = "localhost"
        private const val listeningPort = 10000
    }

    private val message = "méssage to `sªend/-"

    @Test
    fun `acceptSuspend should accept a connection`() {
        val serverSocket = AsynchronousServerSocketChannel.open()
        serverSocket.bind(InetSocketAddress(listeningAddress, listeningPort))
        val clientSocket = AsynchronousSocketChannel.open()
        clientSocket.connect(InetSocketAddress(listeningAddress, listeningPort)).get()
        runBlocking {
            launch(Dispatchers.IO) {
                logger.info("Waiting for connection")
                val acceptedSocket = serverSocket.acceptSuspend()
                logger.info("Connection accepted")
                assertTrue(acceptedSocket.isOpen)
                assertNotNull(acceptedSocket.remoteAddress)
            }
        }
        clientSocket.close()
        serverSocket.close()
    }

    @Test
    fun `acceptSuspend should be sensible to cancellation`() {
        val serverSocket = AsynchronousServerSocketChannel.open()
        serverSocket.bind(InetSocketAddress(listeningAddress, listeningPort))
        serverSocket.use {
            runBlocking {
                val job = launch(Dispatchers.IO) {
                    while (true) {
                        logger.info("Waiting for connection")
                        try {
                            serverSocket.acceptSuspend()
                        } catch (e: CancellationException) {
                            logger.info("acceptSuspend was canceled")
                            break
                        }
                    }
                }
                delay(1000)
                launch {
                    job.cancel()
                }
            }
            assertFalse(serverSocket.isOpen)
        }
    }

    @Test
    fun `readSuspend should read data from the channel`() {
        val buffer = ByteBuffer.allocate(1024)
        val latch = SuspendableCountDownLatch(1)
        val serverSocket = AsynchronousServerSocketChannel.open()
        serverSocket.bind(InetSocketAddress(listeningAddress, listeningPort))
        val clientSocket = AsynchronousSocketChannel.open()
        clientSocket.connect(InetSocketAddress(listeningAddress, listeningPort)).get()
        lateinit var socket: AsynchronousSocketChannel
        runBlocking {
            coroutineScope {
                launch {
                    logger.info("Waiting for connection")
                    socket = serverSocket.acceptSuspend()
                    logger.info("Connection accepted")
                }
            }
            launch(Dispatchers.IO) {
                latch.await()
                logger.info("Waiting for bytes to write")
                val writeLen = socket.writeSuspend(ByteBuffer.wrap(message.toByteArray()))
                logger.info("Sent $writeLen bytes")
            }
            launch(Dispatchers.IO) {
                logger.info("Waiting for bytes to read")
                latch.countDown()
                val readLen = clientSocket.readSuspend(buffer)
                logger.info("Read $readLen bytes")
                buffer.flip()
                val receivedMessage = String(buffer.array(), 0, readLen)
                logger.info("Received message: $receivedMessage")
                assertEquals(receivedMessage, message)
            }
        }
        socket.close()
        clientSocket.close()
        serverSocket.close()
    }

    @Test
    fun `writeSuspend should write data to the channel`() {
        val serverSocket = AsynchronousServerSocketChannel.open()
        serverSocket.bind(InetSocketAddress(listeningAddress, listeningPort))
        val clientSocket = AsynchronousSocketChannel.open()
        clientSocket.connect(InetSocketAddress(listeningAddress, listeningPort)).get()
        runBlocking(Dispatchers.IO) {
            logger.info("Waiting for bytes to write")
            val nBytes = clientSocket.writeSuspend(ByteBuffer.wrap(message.toByteArray()))
            logger.info("Sent $nBytes bytes")
            assertEquals(nBytes, message.toByteArray().size)
        }
        clientSocket.close()
        serverSocket.close()
    }

    @Test
    fun `when readSuspend is canceled, the channel should be closed if the operation was not completed`() {
        val serverSocket = AsynchronousServerSocketChannel.open()
        serverSocket.bind(InetSocketAddress(listeningAddress, listeningPort))
        val clientSocket = AsynchronousSocketChannel.open()
        clientSocket.connect(InetSocketAddress(listeningAddress, listeningPort)).get()
        lateinit var socket: AsynchronousSocketChannel
        runBlocking {
            coroutineScope {
                launch {
                    logger.info("Waiting for connection")
                    socket = serverSocket.acceptSuspend()
                    logger.info("Connection accepted")
                }
            }
            val job = launch(Dispatchers.IO) {
                socket.readSuspend(ByteBuffer.allocate(1024))
            }
            assertTrue(socket.isOpen)
            // wait for the read to start
            delay(1000)
            // wait for the job to be canceled and finished
            job.cancelAndJoin()
            assertFalse(socket.isOpen)
        }
        socket.close()
        serverSocket.close()
    }

    @Test
    fun `when writeSuspend is canceled, the channel should be closed if the operation was not completed`() {
        val serverSocket = AsynchronousServerSocketChannel.open()
        serverSocket.bind(InetSocketAddress(listeningAddress, listeningPort))
        val clientSocket = AsynchronousSocketChannel.open()
        clientSocket.connect(InetSocketAddress(listeningAddress, listeningPort)).get()
        lateinit var socket: AsynchronousSocketChannel
        runBlocking {
            coroutineScope {
                launch {
                    logger.info("Waiting for connection")
                    socket = serverSocket.acceptSuspend()
                    logger.info("Connection accepted")
                }
            }
            // reduce the buffer size make write operations slower
            socket.setOption(StandardSocketOptions.SO_SNDBUF, 100)
            val job = launch(Dispatchers.IO) {
                logger.info("Writing")
                while (true) {
                    try {
                        socket.writeSuspend(ByteBuffer.wrap(randomString(1000).toByteArray()))
                    } catch (e: CancellationException) {
                        logger.info("writeSuspend was canceled")
                        break
                    }
                }
            }
            assertTrue(socket.isOpen)
            // wait for the write to start
            delay(1000)
            logger.info("Canceling write")
            // wait for the job to be canceled and finished
            job.cancelAndJoin()
            assertFalse(socket.isOpen)
        }
        socket.close()
        clientSocket.close()
        serverSocket.close()
    }
}