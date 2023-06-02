package pt.isel.pc.problemsets.set3

import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Provides a suspendable version of [AsynchronousServerSocketChannel.accept] that is sensible to coroutine cancellation.
 */
suspend fun AsynchronousServerSocketChannel.acceptSuspend() =
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
 */
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
 */
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
