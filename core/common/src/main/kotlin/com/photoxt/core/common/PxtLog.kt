package com.photoxt.core.common

import android.util.Log

/**
 * The only logger PhotoXT code may use (spec §0 rule 5, §7).
 *
 * Every message, including the stack trace of a passed [Throwable], goes through [redact] before
 * it reaches logcat, so tokens, e-mail addresses, file names, paths and GPS coordinates are masked
 * even when a caller slips one in. Debug messages are dropped from release builds.
 */
object PxtLog {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, redact(message))
    }

    fun i(tag: String, message: String) {
        Log.i(tag, redact(message))
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, redact(withStackTrace(message, error)))
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, redact(withStackTrace(message, error)))
    }
}

private fun withStackTrace(message: String, error: Throwable?): String =
    if (error == null) message else message + "\n" + error.stackTraceToString()

private const val MEDIA_EXTENSIONS =
    "jpe?g|png|webp|gif|heic|heif|avif|bmp|dng|raw|mp4|mov|mkv|webm|3gp|srt|vtt"

private val redactions: List<Pair<Regex, String>> = listOf(
    Regex("""(?i)\bbearer\s+[\w.~+/-]+=*""") to "Bearer <token>",
    Regex("""\bya29\.[\w.-]+""") to "<token>",
    Regex("""\beyJ[\w-]+\.[\w-]+\.[\w-]+""") to "<token>",
    Regex("""(?i)\b(access_token|refresh_token|id_token|token|key|secret|code)=[^&\s]+""") to
        "$1=<redacted>",
    Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""") to "<email>",
    Regex("""\b(content|file)://\S+""") to "<uri>",
    Regex("""(?<![\w.])/(storage|sdcard|data|mnt)/\S+""") to "<path>",
    // A quoted name may contain spaces; an unquoted one ends at whitespace.
    Regex("""(?i)(["'])[^"'\n]*\.($MEDIA_EXTENSIONS)\1""") to "$1<file>$1",
    Regex("""(?i)[^\s/\\"'<>:]+\.($MEDIA_EXTENSIONS)\b""") to "<file>",
    Regex("""-?\d{1,2}\.\d{3,}\s*,\s*-?\d{1,3}\.\d{3,}""") to "<location>"
)

/** Masks secrets and personal data in [message]. Order matters: URIs and paths before names. */
internal fun redact(message: String): String =
    redactions.fold(message) { text, (pattern, replacement) -> pattern.replace(text, replacement) }
