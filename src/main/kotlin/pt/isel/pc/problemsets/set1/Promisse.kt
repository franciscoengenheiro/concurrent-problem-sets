package pt.isel.pc.problemsets.set1

import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class Promisse<T>: Future<T> {
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        TODO("Not yet implemented")
    }

    override fun isCancelled(): Boolean {
        TODO("Not yet implemented")
    }

    override fun isDone(): Boolean {
        TODO("Not yet implemented")
    }

    override fun get(): T {
        TODO("Not yet implemented")
    }

    override fun get(timeout: Long, unit: TimeUnit): T {
        TODO("Not yet implemented")
    }
}