@file:JvmName("MainKt")

package pt.isel.pc.problemsets.set3.base

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("main")

fun main() {
    logger.info("main started")
    runBlocking {
        App.launch(this)
    }
    logger.info("main ended")
}