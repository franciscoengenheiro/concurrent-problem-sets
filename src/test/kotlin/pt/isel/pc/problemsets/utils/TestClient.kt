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
) : AutoCloseable {

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

    override fun close() {
        socket.close()
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

    override fun equals(other: Any?): Boolean {
        if (other !is TestClient) return false
        return id == other.id && listeningAddress == other.listeningAddress && listeningPort == other.listeningPort
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + listeningAddress.hashCode()
        result = 31 * result + listeningPort
        return result
    }

    override fun toString() = name
}