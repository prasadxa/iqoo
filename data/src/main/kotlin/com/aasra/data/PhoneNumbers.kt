package com.aasra.data

/**
 * Syntax-only validation: 3-15 ASCII digits, an optional leading '+', and
 * spaces/dashes between digit groups. Returns null for names or dial controls.
 * Preserves leading zeros and never guesses a country code. Short codes are
 * allowed; this does not establish that a number exists or is reachable.
 */
fun normalizePhoneNumber(raw: String): String? {
    val value = raw.trim()
    if (!Regex("\\+?[0-9]+(?:[ -]+[0-9]+)*").matches(value)) return null
    val normalized = value.replace(" ", "").replace("-", "")
    return normalized.takeIf { it.count { char -> char in '0'..'9' } in 3..15 }
}
