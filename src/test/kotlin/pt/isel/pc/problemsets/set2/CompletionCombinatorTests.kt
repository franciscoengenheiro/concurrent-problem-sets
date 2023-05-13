package pt.isel.pc.problemsets.set2

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import pt.isel.pc.problemsets.sync.combinator.AggregationError
import pt.isel.pc.problemsets.sync.combinator.CompletionCombinator
import pt.isel.pc.problemsets.sync.lockbased.LockBasedCompletionCombinator
import pt.isel.pc.problemsets.utils.randomTo
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class CompletionCombinatorTests {

    private val errorMsg = "Expected error"

    // Method: all
    @ParameterizedTest(name = "{index} - {0}")
    @MethodSource("implementations")
    fun `Combine all future's execution using a single thread executor`(
        name: String,
        compCombinator: CompletionCombinator,
        executor: ScheduledExecutorService
    ) {
        val start = Instant.now()
        val nrFutures = 25 randomTo 50
        val durationInMillis = 100L randomTo 200L
        val futures = (1L..nrFutures).map {
            delayExecution(executor, Duration.ofMillis(it * durationInMillis)) { true }
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
        compCombinator: CompletionCombinator,
        executor: ScheduledExecutorService
    ) {
        val start = Instant.now()
        val nrFutures = 50 randomTo 100
        val durationInMillisForError = 100L randomTo 200L
        val futures = (1L..nrFutures).map {
            delayExecution(executor, Duration.ofMillis(it * 100)) { true }
        }
        val errorFuture =
            delayExecution<Boolean>(executor, Duration.ofMillis(durationInMillisForError)) {
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


    // Method: any
    @ParameterizedTest(name = "{index} - {0}")
    @MethodSource("implementations")
    fun `Combine any future's execution`(
        name: String,
        compCombinator: CompletionCombinator,
        executor: ScheduledExecutorService
    ) {
        val start = Instant.now()
        val nrFutures = 50 randomTo 100
        val durationInMillis = 100L randomTo 200L
        val futures = (1L..nrFutures).map {
            delayExecution(
                executor,
                Duration.ofMillis(it * durationInMillis)
            ) { it }
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
        compCombinator: CompletionCombinator,
        executor: ScheduledExecutorService
    ) {
        val nrFutures = 20 randomTo 50
        var successCounter = 0
        var failureCounter = 0
        val futures: List<CompletableFuture<Any>> = (1L..nrFutures).map {
            if (it % 2 == 0L) {
                delayExecution(executor) { it }
            } else {
                delayExecution(executor) {
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
        compCombinator: CompletionCombinator,
        executor: ScheduledExecutorService
    ) {
        val nrFutures = 500 randomTo 1000
        val listThrowables = mutableListOf<Throwable>()
        val futures: List<CompletableFuture<Nothing>> = (0L..nrFutures).map {
            val randomTh = randomThrowable
            listThrowables.add(randomTh)
            delayExecution(executor) {
                throw randomTh
            }
        }
        val future = compCombinator.any(futures)
        runCatching {
            future.toCompletableFuture().get()
        }.onFailure {
            // because it was executed inside a thread pool, the exception is wrapped
            assertIs<ExecutionException>(it)
            val throwable = it.cause
            // unwrap the exception
            assertIs<AggregationError>(throwable)
            assertEquals(nrFutures, throwable.throwables.size)
            // ensure all throwables were catched correctly in the aggregation error list
            throwable.throwables.forEach { th ->
                assertContains(listThrowables, th)
            }
        }
    }

    companion object {
        private val singleThreadDelayExecutor = Executors.newSingleThreadScheduledExecutor()
        private val multiThreadDelayExecutor = Executors.newScheduledThreadPool(16)
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
            return (1..5).flatMap {
                listOf(
                    Arguments.of(
                        "Using ${LockBasedCompletionCombinator::class.simpleName} with single-thread executor",
                        LockBasedCompletionCombinator(),
                        singleThreadDelayExecutor
                    ),
                    Arguments.of(
                        "Using ${LockBasedCompletionCombinator::class.simpleName} with multi-thread executor",
                        LockBasedCompletionCombinator(),
                        multiThreadDelayExecutor
                    ),
                    Arguments.of(
                        "Using ${LockFreeCompletionCombinator::class.simpleName} with single-thread executor",
                        LockFreeCompletionCombinator(),
                        singleThreadDelayExecutor
                    ),
                    Arguments.of(
                        "Using ${LockFreeCompletionCombinator::class.simpleName} with multi-thread executor",
                        LockFreeCompletionCombinator(),
                        multiThreadDelayExecutor
                    )
                )
            }.stream()
        }

        @JvmStatic
        fun <T> delayExecution(
            executor: ScheduledExecutorService,
            duration: Duration = Duration.ofMillis(1000L),
            supplier: () -> T
        ): CompletableFuture<T> {
            val cf = CompletableFuture<T>()
            executor.schedule(
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