package com.openfolio.core.utils

import com.google.android.gms.tasks.Task
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

private val DIRECT_EXECUTOR = Executor { runnable -> runnable.run() }

/**
 * Awaits a Google Play Services / ML Kit [Task] without blocking the calling thread,
 * propagating coroutine cancellation cleanly.
 */
internal suspend fun <T> Task<T>.awaitCancellable(): T {
    if (isComplete) {
        val error = exception
        return when {
            error != null -> throw error
            isCanceled -> throw CancellationException("ML Kit task was cancelled.")
            else -> {
                @Suppress("UNCHECKED_CAST")
                result as T
            }
        }
    }
    return suspendCancellableCoroutine { continuation ->
        addOnSuccessListener(DIRECT_EXECUTOR) { value ->
            if (continuation.isActive) {
                continuation.resume(value)
            }
        }
        addOnFailureListener(DIRECT_EXECUTOR) { error ->
            if (continuation.isActive) {
                continuation.resumeWithException(error)
            }
        }
        addOnCanceledListener(DIRECT_EXECUTOR) {
            if (continuation.isActive) {
                continuation.cancel()
            }
        }
    }
}
