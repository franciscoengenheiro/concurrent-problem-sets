package pt.isel.pc.problemsets.utils

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

class TestServer private constructor(
    private val process: Process
) : AutoCloseable {

    private val stdOutQueue = LinkedBlockingQueue<String?>()

    private val readerThread = thread(isDaemon = true) {
        while (true) {
            val line = process.inputReader().readLine() ?: break
            println("server: $line")
            stdOutQueue.put(line)
        }
    }

    fun sendSignal() {
        process.destroy()
    }

    fun join() {
        process.waitFor()
        readerThread.join()
    }

    fun waitFor(pred: (String) -> Boolean) {
        while (true) {
            val line = stdOutQueue.poll(10, TimeUnit.SECONDS)
                ?: throw TimeoutException("timeout waiting for line")
            if (pred(line)) {
                return
            }
        }
    }

    companion object {
        fun start(): TestServer {
            return TestServer(
                ProcessBuilder("build/install/jvm/bin/jvm")
                    .redirectErrorStream(true)
                    .start()
            )
        }
    }

    override fun close() {
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }
}