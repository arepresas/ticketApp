package com.ticketapp.android.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [jsQuote].
 *
 * This function interpolates the session token into a script the
 * WebView executes before the SPA boots. A quoting bug here is not a
 * crash — it is a token that gets truncated, mangled, or read as part
 * of the surrounding JavaScript — so the escape table is pinned
 * explicitly rather than exercised only through a happy path.
 */
class JsQuoteTest {

    @Test
    fun `wraps a plain token in double quotes`() {
        assertEquals("\"abc.def.ghi\"", jsQuote("abc.def.ghi"))
    }

    @Test
    fun `passes an empty string through`() {
        assertEquals("\"\"", jsQuote(""))
    }

    @Test
    fun `escapes backslash so it cannot eat the next character`() {
        assertEquals("\"a\\\\b\"", jsQuote("a\\b"))
    }

    @Test
    fun `escapes double quote so it cannot terminate the literal`() {
        assertEquals("\"a\\\"b\"", jsQuote("a\"b"))
    }

    @Test
    fun `escapes the short-form control characters`() {
        assertEquals("\"a\\nb\"", jsQuote("a\nb"))
        assertEquals("\"a\\rb\"", jsQuote("a\rb"))
        assertEquals("\"a\\tb\"", jsQuote("a\tb"))
        assertEquals("\"a\\bb\"", jsQuote("a\bb"))
        assertEquals("\"a\\fb\"", jsQuote("ab"))
    }

    @Test
    fun `escapes NUL rather than embedding it`() {
        // A literal NUL would terminate the string early in some
        // engines; the \\u form is unambiguous.
        assertEquals("\"\\u0000\"", jsQuote("\u0000"))
    }

    @Test
    fun `escapes other control characters as four hex digits`() {
        assertEquals("\"\\u001b\"", jsQuote("\u001B"))
        assertEquals("\"\\u0001\"", jsQuote("\u0001"))
    }

    @Test
    fun `leaves printable and non-ascii characters alone`() {
        // U+2028 (line separator) is legal inside a string literal
        // since ES2019 and must survive verbatim.
        assertEquals("\"héllo wörld ✅\"", jsQuote("héllo wörld ✅"))
        assertEquals("\"\u2028\u2029\"", jsQuote("\u2028\u2029"))
    }

    @Test
    fun `escapes every dangerous character at once`() {
        // The adversarial shape: a token that tries to close the
        // literal and append its own statement.
        assertEquals(
            "\"\\\"; alert(1); //\"",
            jsQuote("\"; alert(1); //"),
        )
    }

    @Test
    fun `a hostile token round-trips back to its original value`() {
        // The real invariant: what JavaScript reads back out of the
        // literal is exactly what went in. Counting quotes cannot
        // express this — an escaped quote is still a quote character.
        listOf(
            "\";window.__pwned=true;\"",
            "a\\b\"c\nd",
            "\u0000\u001b",
            "plain.jwt.value",
            "'; alert(1); '",
        ).forEach { original ->
            assertEquals(
                "round-trip failed for: $original",
                original,
                unquoteJsLiteral(jsQuote(original)),
            )
        }
    }

    @Test
    fun `only the delimiters are unescaped quotes`() {
        val quoted = jsQuote("\";window.__pwned=true;\"")

        // Walk the literal and collect quotes that are not preceded by
        // an escape character: there must be exactly the two
        // delimiters, or the literal is terminated early.
        val unescaped = quoted.indices.count { index ->
            quoted[index] == '"' && (index == 0 || quoted[index - 1] != '\\')
        }

        assertEquals(2, unescaped)
        assertTrue(quoted.startsWith("\""))
        assertTrue(quoted.endsWith("\""))
    }

    /**
     * Reference inverse of [jsQuote], written independently of the
     * implementation so a bug in one does not cancel out in the other.
     */
    private fun unquoteJsLiteral(literal: String): String {
        require(literal.startsWith("\"") && literal.endsWith("\"")) { "not a literal: $literal" }

        val out = StringBuilder()
        var i = 1
        while (i < literal.length - 1) {
            val ch = literal[i]
            if (ch != '\\') {
                out.append(ch)
                i++
                continue
            }
            when (val escaped = literal[i + 1]) {
                '\\' -> { out.append('\\'); i += 2 }
                '"' -> { out.append('"'); i += 2 }
                'n' -> { out.append('\n'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'b' -> { out.append('\b'); i += 2 }
                'f' -> { out.append('\u000C'); i += 2 }
                'u' -> {
                    val hex = literal.substring(i + 2, i + 6)
                    out.append(hex.toInt(16).toChar())
                    i += 6
                }
                else -> throw AssertionError("unhandled escape: \\$escaped")
            }
        }
        return out.toString()
    }
}