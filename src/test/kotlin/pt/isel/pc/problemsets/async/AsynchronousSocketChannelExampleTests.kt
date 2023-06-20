package pt.isel.pc.problemsets.async

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel

class AsynchronousSocketChannelExampleTests {

    @Test
    fun `connect and send bytes`() {
        // asynchronous code writting in a sequential way
        runBlocking {
            val socketChannel = AsynchronousSocketChannel.open()
            socketChannel.testConnectSuspend(InetSocketAddress("127.0.0.1", 8080))
            logger.info("connect completed")
            val byteBuffer = ByteBuffer.allocate(1024)
            val readLen = socketChannel.testReadSuspend(byteBuffer)
            val s = String(byteBuffer.array(), 0, readLen)
            logger.info("read completed: '{}'", s)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AsynchronousSocketChannelExampleTests::class.java)

        @BeforeAll
        @JvmStatic
        fun checkRequirements() {
            Assumptions.assumeTrue(
                {
                    try {
                        Socket().connect(InetSocketAddress("127.0.0.1", 8080))
                        true
                    } catch (ex: IOException) {
                        false
                    }
                },
                "Requires listening echo server"
            )
        }
    }
}