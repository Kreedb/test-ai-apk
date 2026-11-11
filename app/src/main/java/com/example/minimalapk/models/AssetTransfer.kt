package com.example.minimalapk.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared by catalog downloads, SAF copies and the capped single-model importer. */
internal class AssetTransfer(private val checkActive: () -> Unit = {}) {
    private val lock = Any()
    private var cancelled = false
    private val cancellationActions = mutableSetOf<() -> Unit>()

    fun checkpoint() {
        if (Thread.currentThread().isInterrupted || synchronized(lock) { cancelled }) {
            throw CancellationException("已取消模型传输")
        }
        checkActive()
    }

    /** Disconnects a blocking network read as well as preventing subsequent writes/installation. */
    fun cancel() {
        val actions = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            cancellationActions.toList().also { cancellationActions.clear() }
        }
        actions.forEach { runCatching { it() } }
    }

    fun onCancel(action: () -> Unit): AutoCloseable {
        val cancelNow = synchronized(lock) {
            if (cancelled) true else { cancellationActions += action; false }
        }
        if (cancelNow) runCatching { action() }
        return AutoCloseable { synchronized(lock) { cancellationActions -= action } }
    }

    fun <T : Closeable, R> use(resource: T, action: (T) -> R): R = resource.use {
        val registration = onCancel { resource.close() }
        try {
            checkpoint()
            action(resource)
        } catch (error: Exception) {
            // Closing a cancelled socket/provider stream often throws IOException first.
            checkpoint()
            throw error
        } finally {
            registration.close()
        }
    }

    /**
     * The last cancellation boundary before a storage transaction. No suspension/checkpoint is
     * permitted inside: once accepted, finish the rename (or model+registration) together.
     */
    fun <T> commit(action: () -> T): T {
        checkpoint()
        return action()
    }
}

/**
 * Work remains on the IO worker until its finally blocks finish. The cancellation handler is
 * registered before entering blocking code, so cancelling a coroutine closes its current stream
 * immediately; every read and the final commit also checks that coroutine's actual Job.
 */
internal suspend fun <T> runAssetTransfer(action: (AssetTransfer) -> T): T = withContext(Dispatchers.IO) {
    val owner = currentCoroutineContext()
    suspendCancellableCoroutine { continuation ->
        val transfer = AssetTransfer { owner.ensureActive() }
        continuation.invokeOnCancellation { transfer.cancel() }
        try {
            transfer.checkpoint()
            continuation.resume(action(transfer))
        } catch (error: Throwable) {
            continuation.resumeWithException(error)
        }
    }
}

/**
 * A capped model's local commit and its settings registration must finish together. Preparation
 * and network work happen before this boundary. In particular, withContext(IO)'s prompt return
 * cancellation must never discard a pose spec after its file was installed but before it was saved.
 * [register] runs back on the caller's dispatcher, using its latest model/toggle state.
 */
internal suspend fun <T> commitModelInstallation(install: () -> T, register: (T) -> Unit): T {
    val owner = currentCoroutineContext()
    owner.ensureActive()
    return withContext(NonCancellable) {
        val result = withContext(Dispatchers.IO) {
            owner.ensureActive()
            install()
        }
        register(result)
        result
    }
}
