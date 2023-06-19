package pt.isel.pc.problemsets.utils

import pt.isel.pc.problemsets.set3.base.Messages
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.assertEquals

class TestClient(
    val id: Int,
    private val listeningAddress: String,
    private val listeningPort: Int
) {

    private val socket = Socket()
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    val name = "client-$id"

    init {
        socket.soTimeout = 5_000
    }

    fun connect() {
        socket.connect(InetSocketAddress(listeningAddress, listeningPort))
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