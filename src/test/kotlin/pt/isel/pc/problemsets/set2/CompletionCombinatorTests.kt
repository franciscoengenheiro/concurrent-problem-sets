package pt.isel.pc.problemsets.set2

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import pt.isel.pc.problemsets.sync.combinator.CombinationError
import pt.isel.pc.problemsets.sync.combinator.CompletionCombinator
import pt.isel.pc.problemsets.sync.lockbased.LockBasedCompletionCombinator
import pt.isel.pc.problemsets.sync.lockfree.LockFreeCompletionCombinator
import pt.isel.pc.problemsets.utils.randomTo
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class CompletionCombinatorTests {

    private val errorMsg = "Expected error"

    @ParameterizedTest(name = "{index} - {0}")
    @MethodSource("implementations")
    fun `Combine all future's execution`(
        name: String,
        compCombinator: CompletionCombinator
    ) {
        val start = Instant.now()
        val nrFutures = 25 randomTo 50
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

    @ParameterizedTest(name = "{index} - {0}")
    @MethodSource("implementations")
    fun `Combine all future's execution but one of the futures throws an exception`(
        name: String,
        compCombinator: CompletionCombinator
    ) {
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

    @ParameterizedTest(name = "{index} - {0}")
    @MethodSource("implementations")
    fun `Combine any future's execution`(
        name: String,
        compCombinator: CompletionCombinator
    ) {
        val start = Instant.now()
        val nrFutures = 50 randomTo 100
        val durationInMillis = 100L randomTo 200L
        val futures = (1L..nrFutures).map {
            delayExecution(Duration.ofMillis(it * durationInMillis)) { it }
        }
        val future = compCombinator.any(futures)
        val result = future.toCompletableFuture().get()
        val delta = Duration.between(start, Instant.now())
        // ensure the duration of the test should be at least the duration of the
        // delayed execution of the future that completes first.
        assertTrue{ delta >= Duration.ofMillis(durationInMillis) }
        // has the smaller time to complete
        assertTrue(result == 1L)
    }

    @ParameterizedTest(name = "{index} - {0}")
    @MethodSource("implementations")
    fun `Combine any future's execution but some futures throw an exception`(
        name: String,
        compCombinator: CompletionCombinator
    ) {
        val nrFutures = 20 randomTo 50
        var successCounter = 0
        var failureCounter = 0
        val futures: List<CompletableFuture<Any>> = (1L..nrFutures).map {
            if (it % 2 == 0L) {
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

    @ParameterizedTest(name = "{index} - {0}")
    @MethodSource("implementations")
    fun `Combine any future's execution but all futures throw an exception`(
        name: String,
        compCombinator: CompletionCombinator
    ) {
        val nrFutures = 500 randomTo 1000
        val listThrowables = mutableListOf<Throwable>()
        val futures: List<CompletableFuture<Nothing>> = (0L..nrFutures).map {
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
            val throwable = it.cause
            assertIs<CombinationError>(throwable)
            // ensure all throwables were wrapped correctly in the error list
            throwable.throwables.forEach { th ->
                assertContains(listThrowables, th)
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

        @JvmStatic
        fun implementations(): Stream<Arguments> {
            val repetions = 5L
            val lockBasedImp: Stream<Arguments> = Stream.generate {
                Arguments.of(
                    "Using LockBasedCompletionCombinator",
                    LockBasedCompletionCombinator()
                )
            }.limit(repetions)
            val lockFreeImp: Stream<Arguments> = Stream.generate {
                Arguments.of(
                    "Using LockFreeCompletionCombinator",
                    LockFreeCompletionCombinator()
                )
            }.limit(repetions)
            return Stream.concat(lockBasedImp, lockFreeImp)
        }

        @JvmStatic
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