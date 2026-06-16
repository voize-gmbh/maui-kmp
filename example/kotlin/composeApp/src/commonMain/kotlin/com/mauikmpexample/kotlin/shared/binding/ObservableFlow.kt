package com.mauikmpexample.kotlin.shared.binding

import com.mauikmpexample.kotlin.shared.SharedCoroutineScope
import de.voize.mauikmp.annotation.MauiBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

@MauiBinding
enum class Dispatcher {
    Default,
    Main,
    IO,
}

/**
 * C# observable wrapper for a Kotlin [Flow].
 *
 * Kotlin/Native does not expose `Flow` to the generated MAUI bindings, so we expose a plain,
 * non-suspend class with a callback-based `subscribe` that the bindings support. On the C# side
 * this is adapted to `IObservable<T>` (see `Extra.cs`). `subscribe` returns a cancellation lambda
 * (`() -> Unit`) used to stop collecting.
 *
 * NOTE: depends only on kotlinx.coroutines — never on any .NET/MAUI type.
 */
@MauiBinding
class ObservableFlow<T>(private val flow: Flow<T>) {

    @MauiBinding
    fun subscribe(
        onNext: (T) -> Unit,
        onError: (throwable: Throwable) -> Unit,
        onCompleted: () -> Unit,
        dispatcher: Dispatcher,
    ): () -> Unit {
        val coroutineDispatcher = when (dispatcher) {
            Dispatcher.Default -> Dispatchers.Default
            Dispatcher.Main -> Dispatchers.Main
            Dispatcher.IO -> Dispatchers.IO
        }
        val job = SharedCoroutineScope.launch(coroutineDispatcher) {
            flow.flowOn(Dispatchers.Default)
                .catch { throwable ->
                    if (throwable is Error) throw throwable
                    // `catch` is transparent to the flow's own structural cancellation, but it DOES
                    // deliver a CancellationException that the upstream flow throws as a plain
                    // exception. Rethrow it so cancellation is never reported to onError/the sink
                    // Proven by ObservableFlowCancellationTest.
                    if (throwable is CancellationException) throw throwable
                    onError(throwable)
                }
                .onCompletion { onCompleted() }
                .collect { onNext(it) }
        }
        return { job.cancel() }
    }
}

/**
 * Dedicated boolean variant. Kotlin/Native boxes generic `Boolean` awkwardly across the ObjC
 * boundary, so a non-generic class keeps the generated binding clean for the common boolean-state
 * case.
 */
@MauiBinding
class ObservableBooleanFlow(flow: Flow<Boolean>) {
    private val observableFlow = ObservableFlow(flow)

    @MauiBinding
    fun subscribe(
        onNext: (Boolean) -> Unit,
        onError: (throwable: Throwable) -> Unit,
        onCompleted: () -> Unit,
        dispatcher: Dispatcher,
    ): () -> Unit {
        return observableFlow.subscribe(onNext, onError, onCompleted, dispatcher)
    }
}
