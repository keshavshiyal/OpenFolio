package com.openfolio.core.domain.repository

class DuplicateTransactionException(message: String = "This transaction has already been recorded.") :
    Exception(message)
