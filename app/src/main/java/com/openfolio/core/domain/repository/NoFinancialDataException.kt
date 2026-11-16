package com.openfolio.core.domain.repository

class NoFinancialDataException(
    message: String = "No financial transactions found in this document.",
) : Exception(message)
