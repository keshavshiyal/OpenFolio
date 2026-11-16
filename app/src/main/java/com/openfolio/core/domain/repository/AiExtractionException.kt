package com.openfolio.core.domain.repository

class AiExtractionException(
    reason: String = "Unknown AI error",
    cause: Throwable? = null,
) : Exception("AI extraction failed: $reason", cause)
