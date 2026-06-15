package com.mauikmpexample.kotlin.shared.binding

import com.mauikmpexample.kotlin.shared.SharedCoroutineScope
import de.voize.mauikmp.annotation.MauiBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * C# Task wrapper for a Kotlin [Deferred].
 *
 * Kotlin/Native does not expose `suspend fun`/`Flow` to the generated MAUI bindings (the KSP
 * rejects suspend functions), so we expose async results through a plain, non-suspend class that
 * delivers the value via lambdas — which the bindings do support. On the C# side this is bridged
 * to `System.Threading.Tasks.Task<T>` via `ToVoizeTask`/`FromVoizeTask` (see `Extra.cs`).
 *
 * NOTE: depends only on kotlinx.coroutines — never on any .NET/MAUI type — so the generated
 * iOS framework stays free of a MAUI dependency.
 */
@MauiBinding
open class Task<T> internal constructor(private val deferred: Deferred<T>) {
    @MauiBinding
    fun registerCallbacks(
        onSuccess: (T) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        // Await the work on Default, but invoke the C# callbacks on Main. Invoking a managed
        // (.NET) callback from a Kotlin/Native Default worker thread ties up that worker; doing it
        // repeatedly exhausts the Default pool so later coroutines never start. The main thread is
        // runtime-attached and not part of that pool, so callbacks there are safe (and a UI app
        // wants results on the UI thread anyway).
        SharedCoroutineScope.launch(Dispatchers.Main) {
            try {
                onSuccess(deferred.await())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Error) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    internal suspend fun await(): T {
        return deferred.await()
    }
}

@MauiBinding
class CompletableTask<T> private constructor(private val deferred: CompletableDeferred<T>) : Task<T>(deferred) {

    @MauiBinding
    constructor() : this(CompletableDeferred())

    @MauiBinding
    fun complete(value: T) {
        deferred.complete(value)
    }

    @MauiBinding
    fun completeExceptionally(throwable: Throwable) {
        deferred.completeExceptionally(throwable)
    }
}

internal fun <T> Deferred<T>.asTask(): Task<T> {
    return Task(this)
}

/**
 * Runs a suspend [block] on the default dispatcher and returns a [Task] that can be converted to a
 * `Task<T>` in C#. This is how a Kotlin `suspend fun` is exposed to MAUI.
 */
internal fun <T> runAsync(block: suspend () -> T): Task<T> {
    return SharedCoroutineScope.async(Dispatchers.Default) {
        block()
    }.asTask()
}
