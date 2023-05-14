# Concurrent Programming - Summer 22/23

> This document contains the relevant observations and technical documentation of the problem sets resolution.

- Student: `49428 - Francisco Engenheiro - LEIC41D`

## Table of Contents
- [Set1](#set1)
  - [NAryExchanger](#naryexchanger)
  - [BlockinMessageQueue](#blockingmessagequeue)
  - [ThreadPoolExecutor](#threadpoolexecutor)
  - [ThreadPoolExecutorWithFuture](#threadpoolexecutorwithfuture)
    - [Promise](#promise)
- [Set2](#set2)
  - [CyclicBarrier](#cyclicBarrier)
  - [ThreadSafeContainer](#threadsafecontainer)
  - [ThreadSafeCountedHolder](#threadsafecountedholder)
  - [LockFreeCompletionCombinator](#lockfreecompletioncombinator)
- [Lock-based vs Lock-free Implementations](#lock-based-vs-lock-free-implementations)

## Set1
### NAryExchanger
[Implementation](src/main/kotlin/pt/isel/pc/problemsets/set1/NAryExchanger.kt) | [Tests](src/test/kotlin/pt/isel/pc/problemsets/set1/NAryExchangerTests.kt)

#### Description
This exchanger implementation is similar to the [Java Exchanger](https://docs.oracle.com/javase/7/docs/api/java/util/concurrent/Exchanger.html), but it allows to exchange generic values between 
an arbitrary group of threads instead of just two. It also allows for each thread to specify a willing-to-wait 
timeout for the exchange operation to complete.

The exchanger is able to create multiple groups of threads with the same specified size upon creation,
and each thread can only exchange values with the threads of its group.

A group is completed if the number of threads required to complete the group equals the specified group size.

#### Public interface:
```kotlin
class NAryExchanger<T>(groupSize: Int) {
    @Throws(InterruptedException::class)
    fun exchange(value: T, timeout: Duration): List<T>?
}
```

In the following image, an example can be seen of such iteraction between the exchanger and a set of threads.

| ![NAryExchanger](src/main/resources/set1/nary-exchanger.png) |
|:------------------------------------------------------------:|
|                   *NAryExchanger example*                    |

#### Style of syncronization: 
- For this syncronizer the `Kernel-style` or `Delegation of execution` was used in form of a `Request`, which 
represents a group in this context.
- A delegation of execution was used, because it's easier for the thread that completes the group to signal all the other threads of that group, that such condition is now true, thus completing their request, and as such, the other threads in the group aren't 
required to alter the state of the `Exchanger` or their own state when they return from *await* (as they would in `Monitor Style`).
- Also, the `Kernel-style` allows for the exchanger to keep creating new groups without worrying about the state of the previous ones, as the threads of each group keep a reference to their respective request object.
- The described `Request` is defined as follows:

    ```kotlin
    private class Request<T>(
        val condition: Condition,
        val values: MutableList<T> = mutableListOf(),
        var isGroupCompleted: Boolean = false
    )
    ```

#### Normal execution:
- A thread calls `exchange` and awaits, within a timeout duration, for `groupSize` threads to call `exchange` as well.
- When `groupSize` threads have called `exchange`, the values are exchanged and the threads resume their respective work.

#### Conditions of execution:
- **Paths** - The thread can take two major paths when calling `exchange`:
    - the thread is the last thread to join the group, thus completing it, and as such, it returns with the exchanged values (***fast-path***).
    - a group is not ready to be completed, the thread passively awaits for that condition to be true (***wait-path***). 
- **Giving-up** - While waiting, a thread can *give-up* on the exchange operation if:
    - the thread is interrupted while waiting for the group to be completed and throws an `InterruptedException`.
    - the thread willing-to-wait timeout expires and returns `null`.
- **Additional notes**:
    - If a thread is interrupted but the group is completed, it will still return the exchanged values but will throw an `InterruptedException` if blocked again. 
    - A thread that specifies a timeout of *zero* will not wait for the group to be completed and will return `null` immediately if it did not complete the group.
  
### BlockingMessageQueue
[Implementation](src/main/kotlin/pt/isel/pc/problemsets/set1/BlockingMessageQueue.kt) | [Tests](src/test/kotlin/pt/isel/pc/problemsets/set1/BlockingMessageQueueTests.kt)

#### Description
This syncronizer is a blocking queue,
similar to an [ArrayBlockingQueue](https://docs.oracle.com/javase/7/docs/api/java/util/concurrent/ArrayBlockingQueue.html)
that allows for multiple threads to concurrently enqueue and dequeue messages.
It also allows for each thread to specify a willing-to-wait timeout for the enqueue and dequeue operations to complete.

The term *blocking* refers to the fact that the queue is bounded,
and as such, if a thread tries to enqueue a message when the queue is full,
or tries to dequeue a message when the queue is empty,
it will block until the queue is not full or not empty, respectively.

This type of syncronizer is useful when dealing in scenarios with multiple producers and consumer threads that want to exhange messages,
and as such, it is important to ensure that those messages are enqueued and dequeued in the order of arrival,
because of that the queue was implemented using FIFO (*First In First Out*) ordering.

#### Public interface:
```kotlin
class BlockingMessageQueue<T>(private val capacity: Int) {
    @Throws(InterruptedException::class)
    fun tryEnqueue(message: T, timeout: Duration): Boolean
    @Throws(InterruptedException::class)
    fun tryDequeue(nOfMessages: Int, timeout: Duration): List<T>?
}
```

In the following image, an example can be seen of the iteraction between the blocking queue and a set of producer and consumer threads.

| ![BlockingMessageQueue](src/main/resources/set1/blocking-message-queue.png) |
|:---------------------------------------------------------------------------:|
|                       *BlockingMessageQueue example*                        |

#### Style of syncronization:
- For this syncronizer the `Kernel-style` or `Delegation of execution` was used in form of several `Requests`, which one representing a different condition:
  - `ProducerRequest` - represents a *Producer Thread* request to enqueue a message.
  
    ```kotlin
    private class ProducerRequest<T>(
        val message: T,
        val condition: Condition,
        var canEnqueue: Boolean = false
    )
    ```
  - `ConsumerRequest` - represents a *Consumer Thread* request to dequeue a set of messages.
  
    ```kotlin
    private class ConsumerRequest<T>(
        val nOfMessages: Int,
        val condition: Condition,
        var messages: List<T> = emptyList(),
        var canDequeue: Boolean = false
    )
    ```
- The delegation is used in the sense where the *Consumer thread* that dequeues the messages is the one that signals all *Producer
threads*, that were waiting to enqueue a message, and completes their request if it can be completed, because it altered the 
state of the syncronizer and because of that it might have created conditions that allow other threads to complete their requests. 
This process works in both ways, where a *Producer thread* should complete all *Consumer thread requests* if they can be completed.
- In this context, there's also a special case where if a *Consumer Thread* gives up, either by timeout or interruption, not
only it should remove its request from the *consumer requests queue*,
  but also it should signal all *Consumer Threads*, that were waiting to dequeue a set of messages, and complete their request if it can be completed.

The following image tries to illustrate an example of the described special delegation.

| ![BlockingMessageQueueConsumerSpecialDelegation](src/main/resources/set1/blocking-message-queue-consumer-special-delegation.png) |
|:--------------------------------------------------------------------------------------------------------------------------------:|
|                                                  *Consumer Special delegation*                                                   |

In this example, **Consumer Thread 1** is waiting to dequeue 4 messages, but the *queue* only has 3 messages available.
Since no *Producer Thread* enqueued the final message to complete its request,
within the given timeout, the thread gives up.

In the process of giving up, the thread removes its request from the *consumer requests queue*,
and completes all *Consumer Thread* requests that can be completed, in this example, **Consumer Thread 2** only. 

Since delegation style was used in this syncronizer,
not doing what was described would result in an invalid state of the syncronizer,
where a ***liveliness*** property is violated,
since there are enough messages in the *message queue* to complete the request of **Consumer Thread 2**
but the thread is not signaled and subsequently its request is not completed.

We could think similarly about the *Producer Thread* requests, where if a *Producer Thread* gives up, either by timeout or interruption,
not only it should remove its request from the *producer requests queue*, but also it should signal the next *Producer Threads* in the
queue and complete their request if it could be completed.
But in this case, it's different because the *Producer Thread* that gave-up cannot signal since the request that it 
represents is the same as all other *Producer Thread* requests.

#### Normal execution:
- A thread calls `tryEnqueue` and expects to enqueue a message within the given timeout. 
- A thread calls `tryDequeue` and expects to dequeue a set of messages within the given timeout.

#### Conditions of execution:
`TryEnqueue`:
- **Paths** - The thread can take two major paths when calling this method:
    - **fast-path** 
        - there's a consumer thread that is waiting to dequeue a single message, and as such, the thread delivers the message directly and returns `true`.
        - the *message queue* is not full, and the thread is the head of the *producer requests queue*, the thread enqueues the message and returns `true`.
    - **wait-path** - the *message queue* is full, or the thread is not the head of the *producer requests queue*, and as such, the thread passively awaits to be able to enqueue the message.
- **Giving-up** - While waiting, a thread can *give-up* on the enqueue operation if:
    - the thread is interrupted while waiting for the queue to be not full and throws an `InterruptedException`.
    - the thread willing-to-wait timeout expires and returns `false`.
- **Additional notes**:
    - If a thread is interrupted but another thread completed this thread request to enqueue a message, it will still return `true` but will throw an `InterruptedException` if blocked again.
    - A thread that specifies a timeout of *zero* will not wait and will return `false` immediately if it did not enqueue the message.

`TryDequeue`:
- **Paths** - The thread can take two major paths when calling this method:
    - the *message queue* has at least `nOfMessages` messages, and the thread is the head of the *consumer requests queue*, the thread dequeues the messages and returns them (***fast-path***).
    - the *message queue* has less than `nOfMessages` messages, or the thread is not the head of the *consumer requests queue*, and as such, the thread passively awaits to be able to dequeue the messages (***wait-path***).
- **Giving-up** - While waiting, a thread can *give-up* on the dequeue operation if:
    - the thread is interrupted while waiting for the queue to be not empty and throws an `InterruptedException`.
    - the thread willing-to-wait timeout expires and returns `null`.
- **Additional notes**:
    - If a thread is interrupted but another thread completed this thread request to dequeue a set of messages, it will still return those messages, but will throw an `InterruptedException` if blocked again.
    - A thread that specifies a timeout of *zero* will not wait and will return `null` immediately if it did not dequeue the number of requested messages.

### ThreadPoolExecutor
[Implementation](src/main/kotlin/pt/isel/pc/problemsets/set1/ThreadPoolExecutor.kt) | [Tests](src/test/kotlin/pt/isel/pc/problemsets/set1/ThreadPoolExecutorTests.kt)

#### Description
This syncronizer is similar to the Java [ThreadPoolExecutor](https://docs.oracle.com/javase/7/docs/api/java/util/concurrent/ThreadPoolExecutor.html)
that allows outside threads to delegate the execution of a task to other threads - *worker threads* - that it manages.

The executor has a dynamic worker thread pool size from `0` to `maxThreadPoolSize`.
The worker threads are created lazily,
and as such,
only when a task is delegated to the executor
and there are no available worker threads and the maximum pool size has not been reached, only then a new worker thread is created.

The tasks are executed using the 
[Runnable](https://docs.oracle.com/javase/7/docs/api/java/lang/Runnable.html) interface,
and as such, the executor does not return any value to the caller.
If no work is delegated to the executor, the worker threads will be kept alive, waiting for work, for a maximum of 
`keepAliveTime` before being terminated and removed from the pool by the executor.

#### Public interface
```kotlin
class ThreadPoolExecutor(
    private val maxThreadPoolSize: Int,
    private val keepAliveTime: Duration,
) {
    @Throws(RejectedExecutionException::class)
    fun execute(runnable: Runnable)
    fun shutdown()
    @Throws(InterruptedException::class)
    fun awaitTermination(timeout: Duration): Boolean
}
```

The following image shows how a task (**R**), that is delegated to a worker thread is executed within the thread pool.

| ![ThreadPoolExcecutor](src/main/resources/set1/thread-pool-executor.png) |
|:------------------------------------------------------------------------:|
|                      *ThreadPoolExcecutor example*                       |

#### Style of synchronization
- In this syncronizer, the `Monitor Style` was used to synchronize the *worker threads*.
  Each thread alters the state of the 
syncronizer and doesn't delegate the alteration of that state to another thread.

#### Lifecycle
The executor has a lifecycle that can be described by the following states:

  | ![ThreadPool States](src/main/resources/set1/thread-pool-states.png) |
  |:--------------------------------------------------------------------:|
  |                     *ThreadPoolExecutor states*                      |
 
- **Ready** - the executor is accepting tasks to be executed. Outside threads can delegate tasks to the thread pool using the `execute` method.
- **Shutting down** - the executor is in shutdown mode, and as such, is not accepting tasks to be executed, but it's still executing the tasks that were already delegated to it. This process is started by calling the `shutdown` method.
- **Terminated** - the thread pool has finished the shutdown process and terminates. All tasks that were delegated to it prior to the shutdown process have been executed with success or failure. An outside thread can syncronize with this termination process by calling the `awaitTermination` method.

#### Normal execution:
- A thread calls `execute` and leaves, expecting the task to be executed by a worker thread within the time limit.
- A thread calls `shutdown`, expecting the thread pool to start shutting down.
- A thread calls `awaitTermination` and awaits, for a time duration, for the thread pool to terminate.

#### Conditions of execution:
`shutdown`:
- In the first and only effective call to `shutdown` method, the executor is responsible to signal all the threads waiting,
  for more tasks to be delegated to them that the executor is shutting down, and they should clear the queue of tasks and
  terminate if no more work is available.

`awaitTermination`:
- **Paths** - The thread can take two major paths when calling this method:
    - the thread pool has already terminated, and as such, the thread returns `true` immediately (***fast-path***).
    - the thread pool has not terminated, and as such, the thread passively awaits for the thread pool to terminate (***wait-path***).
- **Giving-up** - While waiting, a thread can *give-up* on the executor shutdown operation if:
    - the thread willing-to-wait timeout expires and returns `false`.
- **Additional notes**:
    - the thread is interrupted while waiting and throws an `InterruptedException`.
    - a thread that specifies a timeout of *zero* will not wait for the executor to shut down and will return `false` immediately.
    - the last thread to terminate is also responsible to signal all the threads waiting for the executor to shut down.


### ThreadPoolExecutorWithFuture
[Implementation](src/main/kotlin/pt/isel/pc/problemsets/set1/ThreadPoolExecutorWithFuture.kt) | [Tests](src/test/kotlin/pt/isel/pc/problemsets/set1/ThreadPoolExecutorWithFutureTests.kt)

#### Description
This syncronizer is similar to the [ThreadPoolExecutor](#threadpoolexecutor), but instead of 
[Runnable](https://docs.oracle.com/javase/7/docs/api/java/lang/Runnable.html) tasks, 
it accepts [Callable](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/Callable.html) tasks
that return a value or throw an exception.

#### Public interface
```kotlin
class ThreadPoolExecutorWithFuture(
    private val maxThreadPoolSize: Int,
    private val keepAliveTime: Duration,
) {
    fun <T> execute(callable: Callable<T>): Future<T>
    fun shutdown()
    @Throws(InterruptedException::class)
    fun awaitTermination(timeout: Duration): Boolean
}
```

The following image shows how a task (**C**),
that is delegated to a worker thread
is executed within the thread pool.
A representative (**F**) of the task execution is returned to the outside thread that delegated the task.

| ![ThreadPoolExcecutorWithFuture](src/main/resources/set1/thread-pool-executor-with-future.png) |
|:----------------------------------------------------------------------------------------------:|
|                            *ThreadPoolExcecutorWithFuture example*                             |

#### Style of synchronization
- In this syncronizer, the `Monitor Style` was used to synchronize the *worker threads*.
Each thread alters the state of the syncronizer when necessary and doesn't delegate the alteration of that state to another thread.
- Although it was used a `Request` to represent the task execution in the thread pool, that request completion
is not being delegated to another thread. Once a worker thread has received a request, it is responsible to 
complete it in any way possible.

The described `Request` is as follows:
```kotlin
private class ExecutionRequest<T>(
    val callable: Callable<T>,
    val result: Promise<T> = Promise()
)
```

### Promise
[Implementation](src/main/kotlin/pt/isel/pc/problemsets/set1/Promise.kt) | [Tests](src/test/kotlin/pt/isel/pc/problemsets/set1/PromiseTests.kt)

#### Description
In order to allow the outside threads to get the result of the task execution,
the `execute` method of [ThreadPoolExecutorWithFuture](#threadpoolexecutorwithfuture) returns a 
[Future](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/Future.html) object.

Instead of using already existing implementations,
this executor uses its own implementation of the `Future` interface - a [Promise](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Promise) -
which provides a *Future* that is explicitly completed, and it can be resolved with a value, rejected with an exception or cancelled.

#### Public interface
```kotlin
class Promise<T> : Future<T> {
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean
    override fun isCancelled(): Boolean
    override fun isDone(): Boolean
    @Throws(InterruptedException::class, CancellationException::class, ExecutionException::class)
    override fun get(): T
    @Throws(TimeoutException::class, InterruptedException::class, CancellationException::class, ExecutionException::class)
    override fun get(timeout: Long, unit: TimeUnit): T
    fun resolve(result: T)
    fun reject(ex: Throwable)
}
```

#### Lifecycle
The promise has a lifecycle that can be described by the following states:

| ![Promise states](src/main/resources/set1/promise-states.png) |
|:-------------------------------------------------------------:|
|                       *Promise states*                        |

- **Pending** - the promise is pending and has not yet produced a result.
- **Resolved** - the computation has completed successfully with the given value.
- **Rejected** - the computation has completed with a failure due to an exception.
- **Cancelled** - promise was cancelled before it could be completed.

Once the *promise* is resolved, rejected or cancelled, it cannot be altered.

#### Style of synchronization
- In this syncronizer, the `Monitor Style` was used in the sense that the thread that alters the state of the promise is responsible to signal all threads that are waiting for that state to be altered for them to evaluate the state of the promise and act accordingly.

#### Normal execution:
- A thread calls `cancel`, expecting the task to be cancelled.
- A thread calls `resolve`, expecting the task to be resolved with the given value.
- A thread calls `reject`, expecting the task to be rejected with the given exception.
- A thread calls `get`, expecting to retrieve the result of the task execution.

#### Conditions of execution:
`get`:
- **Paths** - The thread can take two major paths when calling this method:
    - **fast-path** - the task has already been completed, and as such, the thread receives the result of the task execution.
    - **wait-path** - the task has not been completed, and as such, the thread passively awaits for that condition to be met.
- **Giving-up** - While waiting, a thread can *give-up* on the task execution if:
    - the thread willing-to-wait timeout expires and throws a `TimeoutException`.
    - the thread is interrupted while waiting and throws an `InterruptedException`.
- **Additional notes**: 
    - A thread that specifies a timeout of *zero* and the promise is not yet ready, it will not wait for the promise 
    to be completed and will throw a `TimeoutException`.
    - if a task has been completed:
        - but was cancelled, throws a `CancellationException`.
        - but was rejected, throws an `ExecutionException`.
        - but was resolved, returns the result of the task execution.

## Set2
### CyclicBarrier
[Implementation](src/main/kotlin/pt/isel/pc/problemsets/set2/CyclicBarrier.kt) |
[Tests](src/test/kotlin/pt/isel/pc/problemsets/set2/CyclicBarrierTests.kt)

#### Description
A [CycleBarrier](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CyclicBarrier.html) is a syncronization mechanism
that allows a set of threads to wait for each other to reach a common barrier point.
If provided, a `Runnable` task is executed once the last thread in the set arrives at the barrier.

The barrier is called *cyclic* because it can be re-used again after being broken for the next barrier generation.

Assuming a barrier is not opened yet, it can be *broken* for the following reasons:
- A thread waiting at the barrier is interrupted.
- A thread waiting at the barrier times out while waiting.
- The barrier was resetted, and there was at least one thead waiting at the barrier.
- If the execution of the runnable by the last thread, throws an exception.  
- When a thread sets a timeout of zero and enters a non-broken barrier but does not complete it.

A new generation of the barrier is created *only* when:
- the last thread that enters the barrier and:
    - the runnable task executes successfully.
    - no runnable task was provided.
- the barrier is resetted.

#### Public interface
```kotlin
class CyclicBarrier(
    private val parties: Int,
    private val barrierAction: Runnable?,
) {
    constructor(parties: Int) : this(parties, null)
    @Throws(InterruptedException::class, BrokenBarrierException::class)
    fun await(): Int
    @Throws(InterruptedException::class, TimeoutException::class, BrokenBarrierException::class)
    fun await(timeout: Duration): Int
    fun getNumberWaiting(): Int
    fun getParties(): Int
    fun isBroken(): Boolean
    fun reset()
}
```

The barrier has the following possible states for each barrier generation.

| ![Cyclic Barrier states](src/main/resources/set2/cyclic-barrier-states.png) |
|:---------------------------------------------------------------------------:|
|                 *Cyclic Barrier possible generation states*                 |

#### Style of synchronization
For this syncronizer the `Kernel-style` or `Delegation of execution` was used in form of a `Request` per barrier generation,
  because it's easier
  for the last thread to enter the barrier
  to signal all threads that are waiting for the barrier to be opened and thus completing their request.

This implementation enables the barrier to be reused for the next barrier generation without affecting the prior barrier reference that the other threads have acquired before laying down their request.

As mentioned, the `CyclicBarrier` is a reusable barrier,
and as such, it is necessary to create another instance of the `Request` for the next barrier generation,
and that is done by the last thread to enter the barrier.
This thread is also responsible to execute the runnable task if it exists.
If the execution of the runnable task throws a `throwable`,
the barrier is broken and all threads waiting at the barrier are released with a `BrokenBarrierException`.

Because the threads are always signaled to leave the condition by either resetting the barrier or by opening it, the condition where all threads are waiting for the barrier to be broken is also reused in subsequent barrier generations.

The described `Request` is defined as follows:

```kotlin
private class BarrierRequest(
    var nOfThreadsWaiting: Int = 0,
    var wasBroken: Boolean = false,
    var wasOpened: Boolean = false
)
```

The following image shows a possible representation of the previous states in a barrier with 3 parties. 

| ![Cyclic Barrier](src/main/resources/set2/cyclic-barrier.png) |
|:-------------------------------------------------------------:|
|                   *Cyclic Barrier example*                    |

#### Normal execution:
- A thread calls `await`, and passively awaits indefinitely for the other threads to reach the barrier, in order for it to be opened and the runnable task to be executed if it exists. Returns the arrival index of this thread where:
    - `getParties() - 1` - for first thread to enter the barrier.
    - `0` - for last thread to enter the barrier.
- A thread calls `await` (with *timeout*), and awaits for a specified timeout for the other threads to reach the barrier, with the same behavior as the previous method.
- A thread calls `reset`, and resets the barrier for the next generation.
- A thread calls `getNumberWaiting`, and retrieves the number of threads that are waiting for the barrier to be opened.
- A thread calls `getParties`, and retrieves the number of threads that must invoke `await` in order for the barrier to be opened.
- A thread calls `isBroken`, and retrieves information about whether the barrier has been broken or not.

#### Conditions of execution:
`await`:
- **Paths** - The thread can take three major paths when calling this method:
    - **fast-path** 
        - the current barrier has already been broken, and as such, the thread throws a `BrokenBarrierException`.
        - the barrier has not yet been broken, and this thread is the last one to enter the barrier:
            - if a runnable task was provided and its execution throws a `throwable`, the barrier is broken and all threads waiting at the barrier are released with a `BrokenBarrierException`, and the thread returns the same `throwable`.
            - if a runnable task was not provided, or its execution completes successfully, the barrier is opened, a new generation is created, and the thread returns `0`.
        - A thread that specifies a timeout of *zero* and does not complete the barrier will not wait for that event and throws a `TimeoutException` immediately.
    - **wait-path**
        - the barrier has not yet been broken, and this thread is not the last one to enter the barrier, and as such, the thread passively awaits for that condition to be met. 
- **Giving-up** - While waiting for the barrier to be opened, a thread can *give-up* in the following cases:
    - the thread is interrupted and, as such, throws an `InterruptedException`, ***if and only if*** it was the first thread to be interrupted out of all the threads waiting for the barrier to be broken.
    - the thread is interrupted, and if the barrier was already broken by another thread, throws a `BrokenBarrierException`.
    - the thread timeout expires and throws a `TimeoutException`.
- **Additional notes**:.
    - If the last thread to enter the barrier throws a `throwable` when executing the `Runnable` task, the barrier is broken.
     and all the other threads waiting for the barrier to be broken will throw a `BrokenBarrierException`.

### ThreadSafeContainer
[Implementation](src/main/kotlin/pt/isel/pc/problemsets/set2/ThreadSafeContainer.kt) |
[Tests](src/test/kotlin/pt/isel/pc/problemsets/set2/ThreadSafeContainerTests.kt)

#### Description
A thread-safe container is a container that allows multiple threads to consume the values it 
contains using the `consume` method.
The container receives an array of [AtomicValue](src/main/kotlin/pt/isel/pc/problemsets/set2/AtomicValue.kt)s,
that cannot be empty.
Each value has a number of lives that represent the number of times that value can be consumed by a thread.

A thread that consumes a value from the container
decreases the number of lives of that value by one or returns `null` if the container has no values left to consume.
There's no garantee which value will be consumed by a thread, nor each life.

#### Public interface
```kotlin
class ThreadSafeContainer<T>(
    private val values: Array<AtomicValue<T>>
) {
    fun consume(): T
}
```

The following images illustrate the state of the container before and after a set of threads consume values from it.

| ![Thread-Safe Container before consumption](src/main/resources/set2/thread-safe-container-before-consumption.png) |
|:-----------------------------------------------------------------------------------------------------------------:|
|  ![Thread-Safe Container after consumption](src/main/resources/set2/thread-safe-container-after-consumption.png)  |
|                                          *Thread-Safe Container example*                                          |

#### Style of synchronization
The implementation of this syncronizer does not use explicit or implicit `locks` and relys only on the
[Java Memory model](https://docs.oracle.com/javase/specs/jls/se8/html/jls-17.html) guarantees that are
implemented by the `JVM`.

An implementation that is not *thread-safe*, and that was the starting point of this implementation, can be seen [here](src/main/kotlin/pt/isel/pc/problemsets/unsafe/UnsafeContainer.kt).

#### Normal execution:
- A thread calls `consume`, and consumes a value from the container, if there is any left or returns `null` if 
the container is empty.

#### Conditions of execution:
`consume`:
- **Paths** - The thread can take two major paths when calling this method:
    - **fast-path**
        - the container has no values left to consume, and as such, null is returned.
    - **outer-retry-path**
        - the container has values left to consume, so the thread tries to decrement a life from the current index 
          value until possible. This action is associated with an **inner-retry-path**, because the thread will keep trying 
          to decrement the life of the current value until it is not possible, because some other thread(s) decremented all 
          lives of this value and as such, this thread is forced to leave to the **outer-retry-path**. Back to the outer loop,
          the thread tries to decrement a life of the next value in the array if it exists, or returns null if the array was 
          emptied in the meantime.

### ThreadSafeCountedHolder
[Implementation](src/main/kotlin/pt/isel/pc/problemsets/set2/ThreadSafeCountedHolder.kt) |
[Tests](src/test/kotlin/pt/isel/pc/problemsets/set2/ThreadSafeCountedHolderTests.kt)

#### Description
A thread-safe-counted holder is a container
that holds a `value` that internally has a `counter` that specifies how many times the value was used.
If the counter reaches zero, the value is automatically *closed*, since it implements the 
[Closeable](https://docs.oracle.com/javase/8/docs/api/java/io/Closeable.html) interface.

#### Public interface
```kotlin
class ThreadSafeCountedHolder<T : Closeable>(value: T) {
    fun tryStartUse(): T?
    @Throws(IllegalStateException::class)
    fun endUse()
}
```

The following images illustrate the state of the holder when a thread tries to use the value,
and after a thread decrements the usage counter of the value and
the value is closed because the counter reached zero.

|         ![Thread-Safe Counted Holder Try Use](src/main/resources/set2/thread-safe-counted-holder-try-use.png)         |
|:---------------------------------------------------------------------------------------------------------------------:|
| ![Thread-Safe Counted Holder End Usage And Close](src/main/resources/set2/thread-safe-counted-holder-after-close.png) |
|                                         *Thread-Safe Counted Holder example*                                          |

#### Style of synchronization
The implementation of this syncronizer does not use explicit or implicit `locks` and relys only on the
[Java Memory model](https://docs.oracle.com/javase/specs/jls/se8/html/jls-17.html) guarantees that are
implemented by the `JVM`.

An implementation that is not thread-safe, and that was the starting point of this implementation,
can be seen [here](src/main/kotlin/pt/isel/pc/problemsets/unsafe/UnsafeUsageCountedHolder.kt).

#### Normal execution:
- A thread calls `tryStartUse`, and retrieves the value if it is not closed, incrementing the usage counter.
- A thread calls `endUse`, and decrements the usage counter of the value, closing it if the counter reaches zero.

#### Conditions of execution:
`tryStartUse`:
- **Paths** - The thread can take two major paths when calling this method:
    - **fast-path**
        - the `value` is already null, which means the resource was closed, and as such, null is returned (cannot be reused).
    - **retry-path**
        - the `value` is not closed, so this thread tries to increment the usage counter if possible, and if it is, returns the `value`.
        - while trying to increment the usage counter, the threads sees that the counter is zero, indicates that the resource is closed, null is returned (cannot be reused).
      
`endUse`:
- **Paths** - The thread can take two major paths when calling this method:
    - **fast-path**
        - the `value` is already null, since it was closed, and as such, `IllegalStateException` is thrown.
    - **retry-path**
        - the `value` is not closed, so this thread tries to decrement the usage counter if possible, and if it is, returns immediately.
        - while trying to decrement the usage counter, the threads see that the counter is zero, indicating that some other thread closed the resource, this thread throws `IllegalStateException`.
        - if the thread that decrements the usage counter sees the counter reaching zero, the thread closes the resource and sets the `value` to null.

### LockFreeCompletionCombinator
[Implementation](src/main/kotlin/pt/isel/pc/problemsets/set2/LockFreeCompletionCombinator.kt) |
[Tests](src/test/kotlin/pt/isel/pc/problemsets/set2/CompletionCombinatorTests.kt)

#### Description
A [CompletionCombinator](src/main/kotlin/pt/isel/pc/problemsets/sync/combinator/CompletionCombinator.kt)
that minimizes the usage of locks to synchronize access to shared state.
It uses the `Java Memory Model` guarantees to ensure that the shared state is updated atomically.

An implementation that is *lock-based*,
and that was the starting point of this implementation,
and the motive for the creation of the already mentioned `CompletionCombinator` interface,
is available [here](src/main/kotlin/pt/isel/pc/problemsets/sync/lockbased/LockBasedCompletionCombinator.kt).

An example of a *lock-based* and a *lock-free* implementation can be consulted in this [section](#lock-based-vs-lock-free-implementations).

#### Public interface
```kotlin
class LockFreeCompletionCombinator : CompletionCombinator {

    @Throws(IllegalArgumentException::class)
    override fun <T> all(inputStages: List<CompletionStage<T>>): CompletionStage<List<T>>

    @Throws(AggregationError::class, IllegalArgumentException::class)
    override fun <T> any(inputStages: List<CompletionStage<T>>): CompletionStage<T>
}
```

The following image illustrates the combinator's respective possible output when a list of `CompletableFutures` is provided.

| ![Lock-free Completion Combinator](src/main/resources/set2/lock-free-completion-combinator.png) |
|:-----------------------------------------------------------------------------------------------:|
|                            *Lock-free Completion Combinator example*                            |

#### Style of synchronization
TODO()

#### AggregationError
TODO()

#### Normal Execution
TODO()

#### Conditions of execution
TODO()

### Lock-based vs Lock-free implementations

```kotlin
object LockBasedImplementation {
    val lock: Lock = ReentrantLock()
    var sharedState: Any = Any()
    fun updateState() = lock.withLock {
        logic(sharedState)
    }
}
```

```kotlin
object LockFreeImplementation {
    // or any other variable modifier, class, annotation or final variable that ensures the
    // writing to this reference *happens-before* the read
    val sharedState: AtomicReference<Any> = AtomicReference(Any())
    fun updateState() {
        while (true) {
            val observedState = sharedState.get()
            val nextState = if (condition) {
                logic(observedState)
            } else {
                failureLogic()
            }
            // applies the next state to the shared state if the observed 
            // value corresponds to the value present in the shared state
            if (sharedState.compareAndSet(observedState, nextState)) {
                successLogic()
                return // or any other exit condition of the retry loop when done
            }
            // retry
        }
    }
}
```