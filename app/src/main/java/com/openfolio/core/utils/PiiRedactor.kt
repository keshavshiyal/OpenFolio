package com.openfolio.core.utils

import com.openfolio.core.data.remote.ai.AiCarryOverContext

/**
 * Strips personally identifiable and account-identifying details before transmitting
 * financial statement text or carry-over context to cloud LLM endpoints, while preserving
 * security names, 12-character ISINs, tickers, dates, quantities, prices, and amounts.
 */
object PiiRedactor {
    private val ISIN_REGEX = Regex("^[A-Z]{2}[A-Z0-9]{9}[0-9]$")

    // Indian PAN format: 5 uppercase letters, 4 digits, 1 uppercase letter
    private val PAN_REGEX = Regex("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b")

    // Email addresses
    private val EMAIL_REGEX = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")

    // Indian Aadhaar 12-digit grouped number (e.g. 1234 5678 9012 or 1234-5678-9012)
    private val AADHAAR_REGEX = Regex("\\b\\d{4}[\\s-]\\d{4}[\\s-]\\d{4}\\b")

    // Mobile/phone numbers prefixed with country code or explicit phone label (avoids monetary figures)
    private val LABELED_OR_PLUS91_PHONE_REGEX = Regex(
        "(?i)(\\+91[\\s-]?|\\b(?:mob(?:ile)?|phone|tel|contact)(?:\\s*(?:no\\.?|number|#))?\\s*[:=\\-]?\\s*)([6-9]\\d{4}[\\s-]?\\d{5})\\b",
    )

    // IFSC codes (4 uppercase letters + '0' + 6 alphanumeric chars)
    private val IFSC_REGEX = Regex("\\b[A-Z]{4}0[A-Z0-9]{6}\\b")

    // Context-labeled Folio / Account / Demat / Client / BO ID / UCC / CRN / MICR / masked PAN tokens
    private val LABELED_ACCOUNT_OR_FOLIO_REGEX = Regex(
        "(?i)(\\b(?:folio(?:\\s*(?:no\\.?|number|#|id))?|account(?:\\s*(?:no\\.?|number|id|#))?|a/c(?:\\s*(?:no\\.?|number|#))?|demat(?:\\s*(?:account|a/c))?(?:\\s*(?:no\\.?|number|id|#))?|client(?:\\s*(?:id|code|no\\.?))?|pan(?:\\s*(?:no\\.?|number))?|dp\\s*id|bo\\s*id|ucc|crn|micr|ckyc|kin)(?:\\s*[:=\\-#]+\\s*|\\s+))([A-Z0-9*Xx/._\\-]{4,32})",
    )

    // Labeled personal KYC lines (name, address, guardian, nominee)
    private val LABELED_KYC_LINE_REGEX = Regex(
        "(?im)^(\\s*(?:investor\\s+name|holder\\s+name|account\\s+holder|first\\s+holder|second\\s+holder|address|nominee(?:\\s+name)?|guardian(?:\\s+name)?)\\s*[:=\\-]\\s*)(.+)$",
    )

    fun redactForAi(text: String): String {
        if (text.isBlank()) return text

        var scrubbed = text
        scrubbed = EMAIL_REGEX.replace(scrubbed, "[REDACTED_EMAIL]")
        scrubbed = PAN_REGEX.replace(scrubbed, "[REDACTED_PAN]")
        scrubbed = IFSC_REGEX.replace(scrubbed, "[REDACTED_IFSC]")
        scrubbed = AADHAAR_REGEX.replace(scrubbed, "[REDACTED_AADHAAR]")
        scrubbed = LABELED_OR_PLUS91_PHONE_REGEX.replace(scrubbed) { match ->
            "${match.groupValues[1]}[REDACTED_PHONE]"
        }
        scrubbed = LABELED_KYC_LINE_REGEX.replace(scrubbed) { match ->
            "${match.groupValues[1]}[REDACTED_PII]"
        }
        scrubbed = LABELED_ACCOUNT_OR_FOLIO_REGEX.replace(scrubbed) { match ->
            val label = match.groupValues[1]
            val value = match.groupValues[2]
            val upper = value.uppercase()
            // Preserve valid 12-char ISINs even if near a keyword, and don't touch pure alpha headers.
            val looksSensitiveToken = value.any(Char::isDigit) ||
                upper.contains("XXX") ||
                value.contains('*')
            if (looksSensitiveToken && !ISIN_REGEX.matches(upper)) {
                "$label[REDACTED_ID]"
            } else {
                match.value
            }
        }
        return scrubbed
    }

    fun sanitizeCarryOverContextForAi(context: AiCarryOverContext?): AiCarryOverContext? {
        if (context == null) return null
        return context.copy(
            activeAmc = redactForAi(context.activeAmc),
            activeScheme = redactForAi(context.activeScheme),
            activeFolio = "",
            activeBroker = redactForAi(context.activeBroker),
            activeAccountId = "",
            incompleteTailText = redactForAi(context.incompleteTailText),
            fallbackTailLines = redactForAi(context.fallbackTailLines),
            recentBoundaryTransactions = context.recentBoundaryTransactions.map(::redactForAi),
        )
    }
}
