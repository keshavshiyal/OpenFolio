package com.openfolio.core.data.local

import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transient, memory-only handoff for PDF unlock passwords.
 * Passwords are never persisted to disk, WorkManager Data, or logs, and are zeroed upon consumption.
 */
@Singleton
class PdfPasswordStore @Inject constructor() {
    private val passwords = ConcurrentHashMap<String, CharArray>()

    fun put(documentId: String, password: String) {
        if (password.isBlank()) return
        val previous = passwords.put(documentId, password.toCharArray())
        previous?.let { Arrays.fill(it, '\u0000') }
    }

    fun take(documentId: String): String? {
        val chars = passwords.remove(documentId) ?: return null
        return try {
            String(chars)
        } finally {
            Arrays.fill(chars, '\u0000')
        }
    }

    fun clear(documentId: String) {
        passwords.remove(documentId)?.let { Arrays.fill(it, '\u0000') }
    }
}
