package pt.isel.pc.problemsets.utils

import pt.isel.pc.problemsets.set3.base.Messages
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.assertEquals

class TestClient(val name: String) {

    private val socket = Socket()
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    init {
        socket.soTimeout = 5_000
    }

    fun connect() {
        socket.connect(InetSocketAddress("127.0.0.1", 8080))
        reader = socket.getInputStream().bufferedReader()
        writer = socket.getOutputStream().bufferedWriter()
        assertEquals(Messages.CLIENT_WELCOME, receive())
    }

    fun send(msg: String) {
        val observed = writer
        requireNotNull(observed)
        observed.write(msg)
        observed.newLine()
        observed.flush()
    }

    fun receive(): String? {
        val observed = reader
        requireNotNull(observed)
        return observed.readLine()
    }
}