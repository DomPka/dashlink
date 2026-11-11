package com.dompka.dashlink

/** Text rules for the diagnostics report. Plain Kotlin, so DiagTest can hold them to their word. */
object Diag {
    /** A line containing any of these is about a key or password and is left out entirely. */
    private val secretWords = listOf("crypto", "passw", "psk", "pwd", "wpa", "secret", "key=")

    /**
     * Never put the camera's Wi-Fi password in a report: drops every line that looks like it holds
     * one, and blanks out [secrets] (the password typed into the app) wherever else they turn up.
     */
    fun redact(body: String, secrets: List<String> = emptyList()): String = mask(
        body.lineSequence()
            .filterNot { line -> secretWords.any { line.contains(it, ignoreCase = true) } }
            .joinToString("\n"),
        secrets
    )

    /** Blanks out [secrets] wherever they appear in [text], and nothing else. */
    fun mask(text: String, secrets: List<String>): String {
        var out = text
        for (s in secrets) {
            if (s.length >= 4) out = out.replace(s, "[removed]")
        }
        return out
    }

    /** At most [max] characters, with a note when something was cut. */
    fun limit(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "\n… (${text.length - max} more characters left out)"
}
