package com.openfolio.core.domain.repository

class MissingApiKeyException(
    message: String = "Gemini API Key Required. Please configure your API key in Settings.",
) : IllegalStateException(message)
