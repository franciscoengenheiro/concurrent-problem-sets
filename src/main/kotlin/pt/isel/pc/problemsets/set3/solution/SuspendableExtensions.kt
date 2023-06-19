@file:JvmName("SuspendingExtensionsKt")

package pt.isel.pc.problemsets.set3.solution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletableFuture
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Executes the given [block] function on this resource and ensures that the resource is
 * correctly closed, whether an exception is thrown or not.
 * This function is a suspending version of the [AutoCloseable.use] function.
 *
 * @param block a function to process this [SuspendableAutoCloseable] resource.
 * @return the result of the [block] function invoked on this resource.
 */
@OptIn(ExperimentalContracts::class)
suspend inline fun <T : SuspendableAutoCloseable?, R> T.use(block: (T) -> R): R {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    var exception: Throwable? = null
    try {
        return block(this)
    } catch (e: Throwable) {
        exception = e
        throw e
    } finally {
        when {
            this == null -> {}
            exception == null -> close()
            else ->
                try {
                    close()
                } catch (closeException: Throwable) {
                    // cause.addSuppressed(closeException) // ignored here
                }
        }
    }
}

/**
 * Provides a suspendable version of the [CompletableFuture.await] method.
 * @return the result of the [CompletableFuture] when explicitly completed.
 */
suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { result, error ->
            if (error != null) {
                continuation.resumeWithException(error)
            } else {
                continuation.resume(result)
            }
        }
    }