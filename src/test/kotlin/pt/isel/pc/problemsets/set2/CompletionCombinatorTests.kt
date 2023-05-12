package pt.isel.pc.problemsets.set2

import com.sun.net.httpserver.Authenticator.Success
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.sync.combinator.CombinationError
import pt.isel.pc.problemsets.sync.combinator.CompletionCombinator
import pt.isel.pc.problemsets.sync.lockbased.LockBasedCompletionCombinator
import pt.isel.pc.problemsets.utils.randomTo
import java.io.IOException
import java.lang.ArithmeticException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class CompletionCombinatorTests {

    // TODO("learn more about parametarized tests for both completionCombinator implementations")
    private val compCombinator: CompletionCombinator = LockBasedCompletionCombinator()

    private val errorMsg = "Expected error"

    @RepeatedTest(3)
    fun `Combinate all future's execution`() {
        val start = Instant.now()
        val nrFutures = 10 randomTo 25
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
        val nrFutures = 50 randomTo 100
        val durationInMillisForError = 100L randomTo 200L
        val futures = (1L..nrFutures).map {
            delayExecution(Duration.ofMillis(it * 100)) { true }
        }
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

    @RepeatedTest(3)
    fun `Combinate any future's execution`() {
        val nrFutures = 10 randomTo 25
        val durationInMillis = 100L randomTo 200L
        val futures = (1L..nrFutures).map {
            delayExecution(Duration.ofMillis(it * durationInMillis)) { it }
        }
        val future = compCombinator.any(futures)
        val result = future.toCompletableFuture().get()
        assertTrue(result == 1L)
    }

    @RepeatedTest(3)
    fun `Combinate any future's execution but some futures throw an exception`() {
        val nrFutures = 10 randomTo 25
        var successCounter = 0
        var failureCounter = 0
        val futures: List<CompletableFuture<Any>> = (1L..nrFutures).map {
            if (it % 2 == 0L){
                delayExecution(Duration.ofMillis(100L)) {
                    it
                }
            } else {
                delayExecution(Duration.ofMillis(100L)) {
                    throw randomThrowable
                }
            }
        }
        val future = compCombinator.any(futures)
        runCatching {
            future.toCompletableFuture().get()
        }.onFailure {
            failureCounter++
        }.onSuccess {
            successCounter++
        }
        // ensure that the future was completed with success
        assertEquals(0, failureCounter)
        assertEquals(1, successCounter)
    }

    @RepeatedTest(3)
    fun `Combinate any future's execution but all futures throw an exception`() {
        val nrFutures = 10 randomTo 24
        val listThrowables = mutableListOf<Throwable>()
        val futures: List<CompletableFuture<Nothing>> = (1L..nrFutures).map {
            val randomTh = randomThrowable
            listThrowables.add(randomTh)
            delayExecution(Duration.ofMillis(100L)) {
                throw randomTh
            }
        }
        val future = compCombinator.any(futures)
        runCatching {
            future.toCompletableFuture().get()
        }.onFailure {
            assertIs<ExecutionException>(it)
            assertIs<CombinationError>(it)
            // ensure all throwables were wrapped correctly in the error list
            it.throwables.forEachIndexed { index, th ->
                val expectedTh = listThrowables[index]
                assertInstanceOf(expectedTh::class.java, th::class.java)
            }
        }
    }

    companion object {
        // TODO("might need to use a thread pool with more than one thread, to simulate
        //  the execution of the futures in parallel (without execution delay)")
        private val delayExecutor = Executors.newSingleThreadScheduledExecutor()
        private val listOfThrowables: List<Throwable> = listOf(
            ArithmeticException(),
            ArrayIndexOutOfBoundsException(),
            NullPointerException(),
            ClassCastException(),
            IOException(),
            OutOfMemoryError(),
            StackOverflowError(),
            NoSuchElementException(),
            UnsupportedOperationException(),
            SecurityException()
        )

        private val randomThrowable: Throwable
            get() = listOfThrowables.random()

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