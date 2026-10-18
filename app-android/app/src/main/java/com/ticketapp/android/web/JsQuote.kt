package com.ticketapp.android.web

/**
 * Turns arbitrary text into a JavaScript string literal.
 *
 * ## Why not `org.json.JSONObject.quote`
 *
 * The Android implementation is perfectly correct, but it drags the
 * Android framework into a file that would otherwise be pure logic —
 * and `org.json` is stubbed out in JVM unit tests, so every assertion
 * on the generated script would need Robolectric to run. This is ~20
 * lines with a single job, and it is testable without an emulator.
 *
 * ## What it escapes
 *
 * Backslash and double quote (the two characters that can terminate or
 * reinterpret the literal), the short-form escapes for the control
 * characters JS spells that way, and `\uXXXX` for anything else below
 * U+0020 — including NUL, which would truncate the string if passed
 * through literally.
 *
 * U+2028 and U+2029 are left alone: they are legal inside a JS string
 * literal as of ES2019, and they only act as line terminators to the
 * *parser* outside one.
 */
internal fun jsQuote(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            FORM_FEED -> out.append("\\f")
            else -> if (ch < ' ') {
                out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            } else {
                out.append(ch)
            }
        }
    }
    out.append('"')
    return out.toString()
}

private const val FORM_FEED = ''