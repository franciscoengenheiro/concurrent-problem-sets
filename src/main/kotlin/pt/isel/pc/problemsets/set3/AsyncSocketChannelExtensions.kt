package pt.isel.pc.problemsets.set3

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.jvm.Throws

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
suspend fun AsynchronousSocketChannel.readSuspend(byteBuffer: ByteBuffer): Int =
    suspendCancellableCoroutine { continuation ->
        read(
            byteBuffer,
            null,
            object : CompletionHandler<Int, Unit?> {
                override fun completed(result: Int, attachment: Unit?) {
                    continuation.resume(result)
                }

                override fun failed(exc: Throwable, attachment: Unit?) {
                    continuation.resumeWithException(exc)
                }
            }
        )
    }

/**
 * Provides a suspendable version of [AsynchronousSocketChannel.write] that is sensible to coroutine cancellation.
 * @param byteBuffer the buffer to write from.
 * @throws CancellationException if the coroutine is canceled while suspended.
 */
@Throws(CancellationException::class)
suspend fun AsynchronousSocketChannel.writeSuspend(byteBuffer: ByteBuffer): Int =
    suspendCancellableCoroutine { continuation ->
        write(
            byteBuffer,
            null,
            object : CompletionHandler<Int, Unit?> {
                override fun completed(result: Int, attachment: Unit?) {
                    continuation.resume(result)
                }

                override fun failed(exc: Throwable, attachment: Unit?) {
                    continuation.resumeWithException(exc)
                }
            }
        )
    }
