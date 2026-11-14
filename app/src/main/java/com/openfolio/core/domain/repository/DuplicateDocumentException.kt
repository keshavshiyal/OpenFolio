package com.openfolio.core.domain.repository

class DuplicateDocumentException(fileHash: String) :
    Exception("This document has already been imported (SHA-256: $fileHash).")
