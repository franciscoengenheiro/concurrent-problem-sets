package pt.isel.pc.problemsets.utils

import pt.isel.pc.problemsets.async.testConnectSuspend
import pt.isel.pc.problemsets.line.LineReader
import pt.isel.pc.problemsets.set3.base.Messages
import pt.isel.pc.problemsets.set3.solution.readSuspend
import pt.isel.pc.problemsets.set3.solution.writeLine
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousSocketChannel
import kotlin.test.assertEquals

class TestClient(private val name: String) {

    private val socket = AsynchronousSocketChannel.open()

    suspend fun connect() {
        socket.testConnectSuspend(InetSocketAddress("localhost", 8080))
        assertEquals(Messages.CLIENT_WELCOME, receive())
    }

    suspend fun send(msg: String) {
        socket.writeLine(msg)
    }

    suspend fun receive(): String? {
        val lineReader = LineReader { byteBuffer -> socket.readSuspend(byteBuffer) }
        return lineReader.readLine()
    }
}