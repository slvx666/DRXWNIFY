/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

/**
 * Helps type a phone number for a login form: whatever the user starts with — "8…", "7…", "9…" or
 * "+7…" — becomes "+7 (9xx) xxx-xx-xx" as they type, and [forLogin] hands the service the plain
 * "+7XXXXXXXXXX" form. Numbers of other countries keep a leading "+" and stay unformatted.
 */
internal object PhoneNumberInput {

    private const val MAX_DIGITS = 15

    /** Digits in international form: a Russian number always starts with 7. */
    private fun normalizedDigits(text: String): String {
        val digits = text.filter(Char::isDigit)
        return when {
            digits.isEmpty() -> ""
            // "8 999 …" is how a Russian number is usually written at home; 8 is the trunk prefix.
            digits.startsWith("8") && digits.length <= 11 -> "7" + digits.drop(1)
            // Started straight with the operator code: the country code is implied.
            digits.startsWith("9") -> "7$digits"
            else -> digits
        }.take(MAX_DIGITS)
    }

    /**
     * The text to show for [text]. The closing parenthesis and separators only appear once a digit
     * follows them, so deleting from the end never gets stuck on a character that reappears.
     */
    fun format(text: String): String {
        val digits = normalizedDigits(text)
        if (digits.isEmpty()) return if (text.trimStart().startsWith("+")) "+" else ""
        if (!digits.startsWith("7")) return "+$digits"

        val local = digits.drop(1).take(10)
        return buildString {
            append("+7")
            if (local.isEmpty()) return@buildString
            append(" (").append(local.take(3))
            if (local.length > 3) append(") ").append(local.substring(3, minOf(6, local.length)))
            if (local.length > 6) append("-").append(local.substring(6, minOf(8, local.length)))
            if (local.length > 8) append("-").append(local.substring(8))
        }
    }

    /** "+7XXXXXXXXXX", the form a login service expects; blank when nothing was typed. */
    fun forLogin(text: String): String = normalizedDigits(text).let { if (it.isEmpty()) "" else "+$it" }

    /** A complete Russian number, or any international one of plausible length. */
    fun isComplete(text: String): Boolean {
        val digits = normalizedDigits(text)
        return if (digits.startsWith("7")) digits.length == 11 else digits.length in 8..MAX_DIGITS
    }
}
