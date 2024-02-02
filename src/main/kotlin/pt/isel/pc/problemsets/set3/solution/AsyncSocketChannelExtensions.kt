@file:JvmName("AsyncSocketChannelExtensionsKt")

package pt.isel.pc.problemsets.set3.solution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val logger = LoggerFactory.getLogger("AsyncSocketChannelExtensions")

/**
 * Provides a suspendable version of [AsynchronousServerSocketChannel.accept] that
 * is sensible to coroutine cancellation.
 * If the coroutine is canceled but the accept operation was not completed,
 * then the channel is **closed** immediately to avoid leaking resources.
 * @return the accepted socket channel.
 * @throws CancellationException if the coroutine is canceled while suspended.
 */
@Throws(CancellationException::class)
suspend fun AsynchronousServerSocketChannel.acceptSuspend(): AsynchronousSocketChannel {
    val wasCompleted = AtomicBoolean(false)
    try {
        return suspendCancellableCoroutine { continuation ->
            accept(
                null,
                object : CompletionHandler<AsynchronousSocketChannel, Unit?> {
                    override fun completed(result: AsynchronousSocketChannel, attachment: Unit?) {
                        wasCompleted.set(true)
                        continuation.resume(result)
                    }

                    override fun failed(exc: Throwable, attachment: Unit?) {
                        continuation.resumeWithException(exc)
                    }
                }
            )
        }
    } catch (e: CancellationException) {
        logger.info("accept operation canceled")
        val observedStatus = wasCompleted.get()
        if (!observedStatus) {
            close()
            logger.info("server socket closed")
        }
        throw e
    }
}

/**
 * Provides a suspendable version of [AsynchronousSocketChannel.read] that is sensible to coroutine cancellation.
 * If the coroutine is canceled but the read operation was not completed,
 * then the channel is **closed** immediately to avoid leaking resources.
 * @param byteBuffer the buffer to read into.
 * @return the number of bytes read.
 * @throws CancellationException if the coroutine is canceled while suspended.
 */
@Throws(CancellationException::class)
suspend fun AsynchronousSocketChannel.readSuspend(byteBuffer: ByteBuffer): Int {
    val wasCompleted = AtomicBoolean(false)
    try {
        return suspendCancellableCoroutine { continuation ->
            read(
                byteBuffer,
                null,
                object : CompletionHandler<Int, Unit?> {
                    override fun completed(result: Int, attachment: Unit?) {
                        continuation.resume(result)
                        wasCompleted.set(true)
                    }

                    override fun failed(exc: Throwable, attachment: Unit?) {
                        continuation.resumeWithException(exc)
                    }
                }
            )
        }
    } catch (e: CancellationException) {
        logger.info("read operation canceled")
        val observedStatus = wasCompleted.get()
        if (!observedStatus) {
            close()
            logger.info("client socket closed")
        }
        throw e
    }
}

/**
 * Provides a suspendable version of [AsynchronousSocketChannel.write] that is sensible to coroutine cancellation.
 * If the coroutine is canceled but the write operation was not completed,
 * then the channel is **closed** immediately to avoid leaking resources.
 * @param byteBuffer the buffer to write from.
 * @return the number of bytes written.
 * @throws CancellationException if the coroutine is canceled while suspended.
 */
@Throws(CancellationException::class)
suspend fun AsynchronousSocketChannel.writeSuspend(byteBuffer: ByteBuffer): Int {
    val wasCompleted = AtomicBoolean(false)
    try {
        return suspendCancellableCoroutine { continuation ->
            write(
                byteBuffer,
                null,
                object : CompletionHandler<Int, Unit?> {
                    override fun completed(result: Int, attachment: Unit?) {
                        continuation.resume(result)
                        wasCompleted.set(true)
                    }

                    override fun failed(exc: Throwable, attachment: Unit?) {
                        continuation.resumeWithException(exc)
                    }
                }
            )
        }
    } catch (e: CancellationException) {
        logger.info("write operation canceled")
        val observedStatus = wasCompleted.get()
        if (!observedStatus) {
            close()
            logger.info("client socket closed")
        }
        throw e
    }
}

/**
 * Allows writing a line to a socket channel.
 * Attempting to write to a closed channel will result in an exception.
 * @param line the line to write.
 */
suspend fun AsynchronousSocketChannel.writeLine(line: String) {
    val finalLine = line + System.lineSeparator()
    val byteBuffer = ByteBuffer.wrap(finalLine.toByteArray())
    writeSuspend(byteBuffer)
}