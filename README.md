# Concurrent Programming - Summer 22/23

> This document contains the relevant observations and technical documentation of the problem sets resolution.

> Student: `49428 - Francisco Engenheiro - LEIC41D`

## Table of Contents

- [Set1](#set1)
  - [NaryExchanger](#naryexchanger)

## Set1
### NaryExchanger
This exchanger implementation is similar to the [Java Exchanger](https://docs.oracle.com/javase/7/docs/api/java/util/concurrent/Exchanger.html), but it allows to exchange generic values between an arbitrary group of threads instead of just two. It also allows for each thread to specify a willing-to-wait timeout for the exchange operation to complete.

The exchanger is able to create another group when the previous group was completed, and even if all the threads associated with the old group don't know yet, the group was completed. 

A group is completed if the number of threads required to complete the group equals the specified group size.

In the following image, an example can be seen of such iteraction between the exchanger and a set of threads.


<style>
  .center {
    text-align: center;
  }
</style>


<div class="center">

| ![NAryExchanger](src/main/resources/NAryExchanger.png) |
|:------------------------------------------------------:|
|                *NAryExchanger example*                 |

</div>

Class definition:
```kotlin
class NAryExchanger<T>(groupSize: Int) {
    @Throws(InterruptedException::class)
    fun exchange(value: T, timeout: Duration): List<T>?
}
```

Style of syncronization: 
- For this syncronizer the `Kernel-style` or `Delegation of execution` was used in form of a `Request`, since the thread that completes the group is the one that signals the other threads that such condition is now true, thus completing their request. 
- As such, the other threads in the group aren't required to alter the state of the `Exchanger` when return from the *await*.

    ```kotlin
    private class Request<T>(
        val condition: Condition,
        val values: MutableList<T> = mutableListOf(),
        var isGroupCompleted: Boolean = false
    )
    ```

Normal execution:
- A thread calls `exchange` and waits for `groupSize` threads to call `exchange` as well.
- When `groupSize` threads have called `exchange`, the values are exchanged and the threads resume.

Conditions of execution:
- If a thread calls `exchange` and:
    - it was the last thread to join the group, thus completing it, it then *signals* all the threads of said group, and returns with the exchanged values (***fast-path***).
    - a group is not ready to be completed, the thread passively waits for that condition to be true (***wait-path***). In the meantime if:
      - the thread willing-to-wait time for the exchange to happen expires, `null` is returned and the thread is resumed.
      - the thread is *interrupted* while waiting for the exchange to complete, which means two things might happen, either:
        - the group was completed, and as such, the thread can't give-up and returns the values exchanged.
        - the group wasn't completed, and as such, the thread gives up, by removing the value it gave to the exchanged group, and throws an `InterruptedException`.

A set of tests were created to test this implementation using the `MultiThreadTestHelper` class, and were divided in two groups:
- Without concurrency stress:
  - `Exchanger should return the values received by a thread group`
  - `Exchanger should only operate in thread groups above minimum group size`
  - `Exchanger should not throw an exception if a thread inside of a completed group is interrupted`
  - `Exchanger should throw InterruptedException if a thread inside a uncompleted group is interrupted.`
  - `Exchanger should discard the values received by threads that were interrupted before a group was formed`
  - `Exchanger should return null if a thread willing-to-wait timeout has expired`
- With concurrency stress:
  - `An arbitrary number of threads should be able to exchange values`