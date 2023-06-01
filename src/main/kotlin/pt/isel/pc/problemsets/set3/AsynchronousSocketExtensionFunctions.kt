package pt.isel.pc.problemsets.set3

import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel

/**
 * Provides a suspendable version of [AsynchronousSocketChannel.read] that is sensible to coroutine cancellation.
 */
suspend fun AsynchronousSocketChannel.readSuspend() {
    TODO()
}

/**
 * Provides a suspendable version of [AsynchronousServerSocketChannel.accept] that is sensible to coroutine cancellation.
 */
suspend fun AsynchronousServerSocketChannel.acceptSuspend() {
    TODO()
}