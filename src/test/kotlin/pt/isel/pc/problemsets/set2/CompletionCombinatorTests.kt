package pt.isel.pc.problemsets.set2

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import pt.isel.pc.problemsets.sync.CompletionCombinator
import pt.isel.pc.problemsets.sync.lockbased.LockBasedCompletionCombinator
import pt.isel.pc.problemsets.utils.randomTo
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class CompletionCombinatorTests {

    // TODO("learn more about parametarized teste")
    private val compCombinator: CompletionCombinator = LockBasedCompletionCombinator()

    @RepeatedTest(3)
    fun `Combinate all future's execution`() {
        val start = Instant.now()
        val nrFutures = 10L randomTo 25L
        val durationInMillis = 100L randomTo 200L
        val futures = (1L..nrFutures).map {
            delayExecution(Duration.ofMillis(it * durationInMillis)) { true }
        }
        val allFutures = compCombinator.all(futures)
        val result = allFutures.toCompletableFuture().get()
        val delta = Duration.between(start, Instant.now())
        // ensure the duration of the test should be at least the duration of all
        // future delayed executions summed up.
        assertTrue(delta.toMillis() >= nrFutures * durationInMillis)
        assertTrue(result.all { it })
    }

    @RepeatedTest(3)
    fun `Combinate all future's execution but one of the futures throws an exception`() {
        val start = Instant.now()
        val nrFutures = 50L randomTo 100L
        val durationInMillisForError = 100L randomTo 200L
        val futures = (1L..nrFutures).map {
            delayExecution(Duration.ofMillis(it * 100)) { true }
        }
        val errorMsg = "Expected error"
        val errorFuture =
            delayExecution<Boolean>(Duration.ofMillis(durationInMillisForError)) {
                throw RuntimeException(errorMsg)
            }
        val allFutures = compCombinator.all(futures + errorFuture)
        val result = assertFailsWith<ExecutionException> {
            allFutures.toCompletableFuture().get()
        }
        assertEquals(errorMsg, result.cause?.message)
        val delta = Duration.between(start, Instant.now())
        // ensure the duration of the test should be at least the duration of the
        // delayed execution of the future that throws an exception.
        assertTrue(delta.toMillis() >= durationInMillisForError)
    }

    companion object {
        private val delayExecutor = Executors.newSingleThreadScheduledExecutor()

        fun <T> delayExecution(duration: Duration, supplier: () -> T): CompletableFuture<T> {
            val cf = CompletableFuture<T>()
            delayExecutor.schedule(
                {
                    runCatching {
                        cf.complete(supplier())
                    }.onFailure {
                        cf.completeExceptionally(it)
                    }
                },
                duration.toMillis(),
                TimeUnit.MILLISECONDS
            )
            return cf
        }
    }

}