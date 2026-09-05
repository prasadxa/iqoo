package com.aasra.companion.pipeline

internal enum class ConfirmationAnswer { YES, NO, UNKNOWN }

private val affirmativeResponse = Regex(
    """(?:(?:yes|yeah|yep|ok|okay|confirm|haan|han|hao|हाँ|हां|जी)(?: (?:please|ji|जी|haan|हाँ|हां))?(?: (?:call(?: him| her| them)?|send(?: it)?|go ahead|kar do|kardo|laga do|bhej do|कर दो|भेज दो))?|go ahead|theek hai|thik hai|kar do|kardo|laga do|bhej do|कर दो|भेज दो)""",
)
private val negativeResponse = Regex(
    """\b(nahi|nahin|na|no|nope|not|don't|dont|mat karo|cancel|ruk jao|rehn do|rehen do)\b|नहीं|नही|मत करो|रुको|रद्द""",
)

internal fun classifyConfirmation(text: String): ConfirmationAnswer {
    val normalized = " ${text.lowercase()} "
    return when {
        negativeResponse.containsMatchIn(normalized) -> ConfirmationAnswer.NO
        affirmativeResponse.matches(normalized.replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ").trim().replace(Regex("\\s+"), " ")) -> ConfirmationAnswer.YES
        else -> ConfirmationAnswer.UNKNOWN
    }
}
