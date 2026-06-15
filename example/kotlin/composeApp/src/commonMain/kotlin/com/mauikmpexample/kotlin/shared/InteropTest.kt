package com.mauikmpexample.kotlin.shared

import com.mauikmpexample.kotlin.shared.binding.ObservableBooleanFlow
import com.mauikmpexample.kotlin.shared.binding.ObservableFlow
import com.mauikmpexample.kotlin.shared.binding.Task
import com.mauikmpexample.kotlin.shared.binding.runAsync
import de.voize.mauikmp.annotation.MauiBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end interop test surface for the MAUI example app.
 *
 * Every scenario the example app exercises (suspend functions, flows, error propagation, C#
 * lambdas/callbacks, nullables, and threading) is exposed here through the binding-friendly
 * [Task]/[ObservableFlow] abstractions — the toolkit does not expose `suspend`/`Flow` directly.
 */
@MauiBinding
class InteropTest @MauiBinding constructor() {

    // --- Suspend functions, exposed as Task<T> (await on the C# side) ---

    @MauiBinding
    fun suspendString(): Task<String> = runAsync {
        delay(1.seconds)
        "Hello from a suspend function"
    }

    @MauiBinding
    fun suspendEcho(value: String): Task<String> = runAsync {
        delay(500.milliseconds)
        "echo: $value"
    }

    /** Returns a nullable result to verify null marshalling through an async boundary. */
    @MauiBinding
    fun suspendNullableString(returnNull: Boolean): Task<String?> = runAsync {
        delay(300.milliseconds)
        if (returnNull) null else "not null"
    }

    @MauiBinding
    fun suspendUnit(): Task<Unit> = runAsync {
        delay(500.milliseconds)
    }

    /** Throws after a delay — the C# side must observe a faulted Task / caught exception. */
    @MauiBinding
    fun failingSuspend(): Task<String> = runAsync {
        delay(300.milliseconds)
        throw RuntimeException("Suspend function failed intentionally")
    }

    // --- Flows, exposed as ObservableFlow<T> (IObservable on the C# side) ---

    /** Emits an incrementing counter every second, forever (until unsubscribed). */
    @MauiBinding
    fun intEvents(): ObservableFlow<Int> = ObservableFlow(
        flow {
            var i = 0
            while (true) {
                emit(i++)
                delay(1.seconds)
            }
        },
    )

    @MauiBinding
    fun dataClassEvents(): ObservableFlow<DemoData> = ObservableFlow(
        flow {
            var i = 0
            while (true) {
                emit(DemoData(i, "item-$i", DemoData.Nested(i % 2 == 0)))
                i++
                delay(1.seconds)
            }
        },
    )

    /** Emits 1, then 2, then fails — verifies error propagation through a flow. */
    @MauiBinding
    fun failingEvents(): ObservableFlow<Int> = ObservableFlow(
        flow {
            emit(1)
            delay(300.milliseconds)
            emit(2)
            delay(300.milliseconds)
            throw RuntimeException("Flow failed intentionally")
        },
    )

    /** Emits very quickly — target for the subscribe/cancel stress test. */
    @MauiBinding
    fun fastEvents(): ObservableFlow<Int> = ObservableFlow(
        flow {
            var i = 0
            while (true) {
                emit(i++)
                delay(5)
            }
        },
    )

    // --- Callbacks / lambdas: pass a C# lambda into Kotlin and verify it is invoked ---

    @MauiBinding
    fun withCallback(onResult: (String) -> Unit) {
        onResult("called synchronously from Kotlin")
    }

    @MauiBinding
    fun withParameterizedCallback(times: Int, onEach: (Int) -> Unit) {
        repeat(times) { onEach(it) }
    }

    // --- Synchronous error propagation ---

    @MauiBinding
    fun throwError() {
        throw IllegalStateException("Synchronous Kotlin error")
    }

    // --- Nullable across the boundary (synchronous) ---

    @MauiBinding
    fun nullableRoundtrip(value: String?): String? = value
}

/**
 * SDK init/teardown/re-init lifecycle + threading demo.
 *
 * The example app holds a single instance and toggles initialization state, observed through a
 * [ObservableBooleanFlow] backed by a [MutableStateFlow] (so new subscribers get the latest value).
 * [doBackgroundWork] runs on a background dispatcher and reports back via the Task callback — the
 * C# side must marshal the result onto the UI thread.
 */
@MauiBinding
class DemoSdk @MauiBinding constructor() {
    private val _initialized = MutableStateFlow(false)

    // NOTE: method names must NOT start with the ObjC `init`/`new`/`copy`/`alloc` families.
    // Kotlin/Native and the maui-kmp KSP mangle those differently (e.g. `init()` -> `doInit`),
    // producing a selector mismatch that crashes at runtime with "unrecognized selector". Hence
    // `start`/`stop`/`restart`/`startedState` instead of `init`/`teardown`/`reInit`/`initializedState`.
    @MauiBinding
    fun startedState(): ObservableBooleanFlow = ObservableBooleanFlow(_initialized)

    @MauiBinding
    fun start() {
        _initialized.value = true
    }

    @MauiBinding
    fun stop() {
        _initialized.value = false
    }

    @MauiBinding
    fun restart() {
        _initialized.value = false
        _initialized.value = true
    }

    @MauiBinding
    fun doBackgroundWork(): Task<String> = runAsync {
        delay(500.milliseconds)
        "background work finished off the UI thread"
    }
}

@Serializable
data class DemoData(
    val id: Int,
    val label: String,
    val nested: Nested,
) {
    @Serializable
    data class Nested(val value: Boolean)
}
