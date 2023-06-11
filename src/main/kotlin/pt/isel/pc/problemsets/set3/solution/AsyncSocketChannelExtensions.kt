package pt.isel.pc.problemsets.set3.solution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Provides a suspendable version of [AsynchronousServerSocketChannel.accept] that is sensible to coroutine cancellation.
 * @throws CancellationException if the coroutine is canceled while suspended.
 */
@Throws(CancellationException::class)
suspend fun AsynchronousServerSocketChannel.acceptSuspend(): AsynchronousSocketChannel =
    suspendCancellableCoroutine { continuation ->
        accept(
            null,
            object : CompletionHandler<AsynchronousSocketChannel, Unit?> {
                override fun completed(result: AsynchronousSocketChannel, attachment: Unit?) {
                    continuation.resume(result)
                }

                override fun failed(exc: Throwable, attachment: Unit?) {
                    continuation.resumeWithException(exc)
                }
            }
        )
    }

/**
 * Provides a suspendable version of [AsynchronousSocketChannel.read] that is sensible to coroutine cancellation.
 * @param byteBuffer the buffer to read into.
 * @throws CancellationException if the coroutine is canceled while suspended.
 */
@Throws(CancellationException::class)
suspend fun AsynchronousSocketChannel.readSuspend(byteBuffer: ByteBuffer): Int {
    val wasCompleted = AtomicBoolean(false)
    try {
        return suspendCancellableCoroutine { continuation ->
            read(byteBuffer, null,
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
        val observedStatus = wasCompleted.get()
        if (!observedStatus) {
            // if the coroutine was canceled and the read operation was not completed, then we must close the channel
            // to avoid leaking resources.
            close()
        }
        throw e
    }
}

/**
 * Provides a suspendable version of [AsynchronousSocketChannel.write] that is sensible to coroutine cancellation.
 * @param byteBuffer the buffer to write from.
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
        val observedStatus = wasCompleted.get()
        if (!observedStatus) {
            // if the coroutine was canceled and the write operation was not completed, then we must close the channel
            // to avoid leaking resources.
            close()
        }
        throw e
    }
}

/**
 * Allows writing a line to the socket channel.
 * @param line the line to write.
 */
suspend fun AsynchronousSocketChannel.writeLine(line: String) {
    val byteBuffer = ByteBuffer.wrap(line.toByteArray())
    writeSuspend(byteBuffer)
}
