package com.openfolio.core.domain.repository

class EmptyExtractedTextException(
    message: String = "No text could be extracted from this document. It may be a scanned image.",
) : Exception(message)
