package pt.isel.pc.problemsets.async

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

suspend fun AsynchronousSocketChannel.testConnectSuspend(address: InetSocketAddress) {
    suspendCoroutine<Unit> { continuation ->
        this.connect(
            address,
            null,
            object : CompletionHandler<Void?, Unit?> {
                override fun completed(result: Void?, attachment: Unit?) {
                    continuation.resume(Unit)
                }

                override fun failed(exc: Throwable, attachment: Unit?) {
                    continuation.resumeWithException(exc)
                }
            }
        )
    }
}

suspend fun AsynchronousSocketChannel.testReadSuspend(byteBuffer: ByteBuffer): Int {
    return suspendCoroutine { continuation ->
        this.read(
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
}