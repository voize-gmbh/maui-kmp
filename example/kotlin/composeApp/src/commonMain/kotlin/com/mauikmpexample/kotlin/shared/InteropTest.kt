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
class InteropTest @MauiBinding @Throws(Exception::class) constructor() {

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
    @Throws(Exception::class)
    fun withCallback(onResult: (String) -> Unit) {
        onResult("called synchronously from Kotlin")
    }

    @MauiBinding
    @Throws(Exception::class)
    fun withParameterizedCallback(times: Int, onEach: (Int) -> Unit) {
        repeat(times) { onEach(it) }
    }

    // --- Synchronous error propagation ---

    @MauiBinding
    @Throws(Exception::class)
    fun throwError() {
        throw IllegalStateException("Synchronous Kotlin error")
    }

    /**
     * Annotated `@Throws(Exception::class)` like every sync binding, yet throws a [kotlin.Error]
     * (not an [Exception]). This must STILL terminate the process — `@Throws(Exception::class)`
     * only bridges [Exception]s to NSError; `Error` stays fatal and is never catchable in C#.
     */
    @MauiBinding
    @Throws(Exception::class)
    fun throwFatalError() {
        throw Error("Synchronous fatal error (must crash, never catchable)")
    }

    // --- Nullable across the boundary (synchronous) ---

    @MauiBinding
    @Throws(Exception::class)
    fun nullableRoundtrip(value: String?): String? = value
}

/**
 * A class whose constructor throws synchronously. Verifies that a throwing constructor on iOS is
 * caught in C# (via @Throws → NSError) instead of terminating the process. The C# host observes it
 * through the generated factory wrapper (it surfaces as a catchable NSErrorException); there is no
 * Kotlin-side sink for synchronous errors — the C# host catches them at the call site.
 */
@MauiBinding
class ThrowingCtor @MauiBinding @Throws(Exception::class) constructor() {
    init {
        error("Constructor failed intentionally")
    }
}

/**
 * A trivial value holder whose constructor and getter never throw. `canThrow = false` opts them out
 * of the sync @Throws requirement, so KSP keeps the plain construction path: no factory, no
 * [DisableDefaultCtor], the C# host still calls `new SharedNonThrowingValue("x")` directly.
 */
@MauiBinding
class NonThrowingValue
    @MauiBinding(canThrow = false)
    constructor(private val value: String) {
        @MauiBinding(canThrow = false)
        fun getValue(): String = value
    }

// ─── Bug-regression tests  ────────────────────────────────────────────────────────

/** Typealias for a completion callback — used to test that KSP expands the alias before deciding
 *  where K/N inserts the NSError out-param. Without the fix, error would land at the end, which
 *  diverges from the selector K/N actually emits, causing an "unrecognized selector" crash. */
typealias OnStringResult = (String) -> Unit

/**
 * Regression for the typealias-callback NSError-placement bug.
 *
 * `withAliasCallback` has [OnStringResult] (a typealias of `(String)->Unit`) as its first (and only)
 * parameter. K/N places the NSError out-param BEFORE the first block, so the selector must be
 * `withAliasCallbackAndReturnError:onResult:`, not `withAliasCallbackOnResult:error:`.
 * Without the typealias-expansion fix in `errorParameterIndex`, KSP would pick the wrong index and
 * produce a mismatched selector → "unrecognized selector" at runtime.
 */
@MauiBinding
class TypeAliasCallbackTest @MauiBinding @Throws(Exception::class) constructor() {
    @MauiBinding
    @Throws(Exception::class)
    fun withAliasCallback(onResult: OnStringResult) {
        onResult("called via typealias callback")
    }
}

/**
 * Regression for the constructor-with-block-first-param baseName bug.
 *
 * The constructor's only parameter is a lambda (block), so `errorParameterIndex` returns 0.
 * The selector must therefore be `initAndReturnError:onReady:` — the baseName is `init`, not
 * `initWith` (there is no real param to attach "With" to when error is first).
 * Without the fix, KSP emitted `initWithAndReturnError:onReady:`, which K/N never produces →
 * bgen can't link it → crash at startup.
 */
@MauiBinding
class BlockFirstCtorTest @MauiBinding @Throws(Exception::class) constructor(onReady: () -> Unit) {
    init { onReady() }

    @MauiBinding(canThrow = false)
    fun ping(): String = "pong"
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
class DemoSdk @MauiBinding @Throws(Exception::class) constructor() {
    private val _initialized = MutableStateFlow(false)

    // NOTE: method names must NOT start with the ObjC `init`/`new`/`copy`/`alloc` families.
    // Kotlin/Native and the maui-kmp KSP mangle those differently (e.g. `init()` -> `doInit`),
    // producing a selector mismatch that crashes at runtime with "unrecognized selector". Hence
    // `start`/`stop`/`restart`/`startedState` instead of `init`/`teardown`/`reInit`/`initializedState`.
    @MauiBinding
    fun startedState(): ObservableBooleanFlow = ObservableBooleanFlow(_initialized)

    @MauiBinding
    @Throws(Exception::class)
    fun start() {
        _initialized.value = true
    }

    @MauiBinding
    @Throws(Exception::class)
    fun stop() {
        _initialized.value = false
    }

    @MauiBinding
    @Throws(Exception::class)
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
