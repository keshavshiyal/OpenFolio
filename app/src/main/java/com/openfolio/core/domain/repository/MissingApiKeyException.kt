package com.openfolio.core.domain.repository

class MissingApiKeyException(
    message: String = "Gemini API key is missing. Please configure it in Settings.",
) : IllegalStateException(message)
