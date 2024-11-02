package info.skyblond.fic

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.rendering.TextStyle
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

object SimpleLogger {
    private val dateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .append(DateTimeFormatter.ISO_LOCAL_DATE)
        .appendLiteral('T')
        .appendValue(ChronoField.HOUR_OF_DAY, 2)
        .appendLiteral(':')
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .optionalStart()
        .appendLiteral(':')
        .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 9, 9, true)
        .parseLenient()
        .appendOffsetId()
        .parseStrict()
        .toFormatter()

    enum class LogLevel {
        TRACE, DEBUG, INFO, WARN, ERROR;

        fun isEnabled(level: LogLevel): Boolean = when (this) {
            TRACE -> true
            DEBUG -> level == ERROR || level == WARN || level == INFO || level == DEBUG
            INFO -> level == ERROR || level == WARN || level == INFO
            WARN -> level == ERROR || level == WARN
            ERROR -> level == ERROR
        }
    }

    @Volatile
    var logLevel: LogLevel = System.getProperty("simpleLogger.logLevel")?.lowercase()?.let { p ->
        LogLevel.entries.find { it.name.lowercase() == p }
    } ?: LogLevel.INFO

    fun getTime(): String = Instant.now().atZone(ZoneOffset.UTC).format(dateTimeFormatter)

    private fun CliktCommand.resolveStyle(level: LogLevel): TextStyle? = when (level) {
        LogLevel.TRACE -> null
        LogLevel.DEBUG -> null
        LogLevel.INFO -> terminal.theme.info
        LogLevel.WARN -> terminal.theme.warning
        LogLevel.ERROR -> terminal.theme.danger
    }

    private fun CliktCommand.log(level: LogLevel, block: () -> String) {
        if (logLevel.isEnabled(level)) {
            val text = "[${getTime()}][$level] - ${block()}"
            val styled = resolveStyle(level)?.let { it(text) } ?: text
            echo(styled, err = level == LogLevel.ERROR)
        }
    }

    fun CliktCommand.trace(block: () -> String) {
        log(LogLevel.TRACE, block)
    }

    fun CliktCommand.debug(block: () -> String) {
        log(LogLevel.DEBUG, block)
    }

    fun CliktCommand.info(block: () -> String) {
        log(LogLevel.INFO, block)
    }

    fun CliktCommand.warn(block: () -> String) {
        log(LogLevel.WARN, block)
    }

    fun CliktCommand.error(block: () -> String) {
        log(LogLevel.ERROR, block)
    }
}