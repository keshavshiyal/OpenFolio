package com.openfolio.core.domain.repository

class AiExtractionException(
    reason: String = "Unknown AI error",
    cause: Throwable? = null,
) : Exception(
    if (reason.startsWith("AI ")) reason else "AI extraction failed: $reason",
    cause,
)
