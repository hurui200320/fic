package info.skyblond.fic

import com.github.ajalt.clikt.core.CliktError
import java.io.File

val b3sumPath = System.getProperty("b3sum.path")
    ?: throw CliktError("Property `b3sum.path` not set")

private fun callB3sum(vararg args: String): String {
    val p = ProcessBuilder(b3sumPath, *args).start()
    val returnValue = p.waitFor()
    if (returnValue != 0) {
        throw CliktError("b3sum returned non-zero: $returnValue")
    }
    return p.inputReader().readText().trim()
}

fun testB3sum(): String = callB3sum("-V")

fun File.b3sum(): String = callB3sum("-l", "32", "--no-names", this.absolutePath)

/**
 * Calculate the MB/s for a give file. Both [start] and [end] is in milliseconds.
 * */
fun File.calculateRate(start: Long, end: Long): Double {
    // dt in second
    val dt = (end - start).coerceAtLeast(1) / 1000.0
    // size in MB
    val size = this.length() / 1024.0 / 1024.0
    return size / dt
}