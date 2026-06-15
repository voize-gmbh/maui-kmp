using System.Collections.Immutable;
using Foundation;

namespace Voize
{
    /// <summary>Disposable that runs an <see cref="Action"/> on dispose (used to cancel flow subscriptions).</summary>
    class Disposable : IDisposable
    {
        private readonly Action _action;
        public Disposable(Action action) => _action = action;
        public void Dispose() => _action();
    }

    /// <summary>An error surfaced by an async adapter (Task/ObservableFlow), with its originating context.</summary>
    public sealed record AdapterError(Exception Exception, string Context);

    /// <summary>
    /// Hot, multicast publisher of async-adapter errors — the C# equivalent of a Kotlin SharedFlow.
    /// Replaces the old global <c>MauiKmp.onError</c> sink: instead of a single mutable callback, any
    /// number of subscribers observe every error the Task/ObservableFlow adapters surface, alongside
    /// the per-subscription <c>onError</c>. Cancellation is never published — the adapters rethrow
    /// <c>CancellationException</c> upstream so it never reaches here.
    /// </summary>
    public static class ErrorBus
    {
        private static readonly object _gate = new();
        private static ImmutableList<IObserver<AdapterError>> _observers = ImmutableList<IObserver<AdapterError>>.Empty;

        /// <summary>Subscribe to observe every async-adapter error. Dispose to stop.</summary>
        public static IObservable<AdapterError> Errors { get; } = new BusObservable();

        internal static void Publish(Exception exception, string context)
        {
            var error = new AdapterError(exception, context);
            foreach (var observer in _observers)
            {
                observer.OnNext(error);
            }
        }

        private sealed class BusObservable : IObservable<AdapterError>
        {
            public IDisposable Subscribe(IObserver<AdapterError> observer)
            {
                lock (_gate) _observers = _observers.Add(observer);
                return new Disposable(() => { lock (_gate) _observers = _observers.Remove(observer); });
            }
        }
    }

    /// <summary>
    /// Hand-written C# support layer for the maui-kmp bindings. The toolkit only generates the raw
    /// ObjC binding surface (ApiDefinitions.cs); these helpers adapt the binding-friendly
    /// Task/ObservableFlow abstractions to idiomatic .NET (<see cref="Task{T}"/> and
    /// <see cref="IObservable{T}"/>).
    /// </summary>
    public static class Extensions
    {
        public static Exception ToException(this SharedKotlinThrowable throwable)
        {
            return new Exception(throwable.Message);
        }

        /// <summary>
        /// Throws the <see cref="NSError"/> (if any) as a managed exception. This is the idiomatic
        /// way to consume the `out NSError` of a binding method generated from a Kotlin function
        /// annotated <c>@Throws(Exception::class)</c>: <c>obj.Foo(out var error); error.ThrowIfError();</c>.
        /// Kotlin <c>Exception</c>s become a catchable <see cref="NSErrorException"/> instead of
        /// terminating the process; <c>kotlin.Error</c> still crashes (no NSError is produced).
        /// </summary>
        public static void ThrowIfError(this NSError? error)
        {
            if (error != null)
            {
                throw new NSErrorException(error);
            }
        }

        public static SharedKotlinThrowable ToKotlinThrowable(this Exception exception)
        {
            return new SharedKotlinThrowable(exception.Message);
        }

        /// <summary>Awaits a Kotlin <c>Task&lt;T&gt;</c>, returning the raw result (an NSObject, or null for Unit).</summary>
        public static Task<NSObject?> ToTask(this SharedTask voizeTask)
        {
            var tcs = new TaskCompletionSource<NSObject?>();
            voizeTask.RegisterCallbacks(
                value => tcs.TrySetResult(value),
                throwable =>
                {
                    var exception = throwable.ToException();
                    ErrorBus.Publish(exception, "Task");
                    tcs.TrySetException(exception);
                }
            );
            return tcs.Task;
        }

        /// <summary>Awaits a Kotlin <c>Task&lt;T&gt;</c> whose result is a reference type (e.g. a Shared data class).</summary>
        public static async Task<T?> ToTask<T>(this SharedTask voizeTask) where T : NSObject
        {
            var value = await voizeTask.ToTask();
            return (T?)value;
        }

        /// <summary>Awaits a Kotlin <c>Task&lt;String&gt;</c> and converts the NSString result to a .NET string.</summary>
        public static async Task<string?> ToStringTask(this SharedTask voizeTask)
        {
            var value = await voizeTask.ToTask();
            return (value as NSString)?.ToString();
        }
    }

    /// <summary>Adapts a Kotlin <c>ObservableFlow&lt;T&gt;</c> to <see cref="IObservable{T}"/>.</summary>
    public partial class SharedObservableFlow : IObservable<NSObject?>
    {
        public IDisposable Subscribe(IObserver<NSObject?> observer)
        {
            var cancel = Subscribe(
                value => observer.OnNext(value),
                throwable =>
                {
                    var exception = throwable.ToException();
                    ErrorBus.Publish(exception, "ObservableFlow");
                    observer.OnError(exception);
                },
                observer.OnCompleted,
                // Deliver on Main: invoking the managed onNext callback from a Kotlin/Native
                // Default worker thread ties up that worker, and repeated subscriptions exhaust the
                // pool. The main thread is runtime-attached, so callbacks there are reliable (and a
                // UI app wants emissions on the UI thread).
                SharedDispatcher.Main
            );
            return new Disposable(() => cancel());
        }
    }

    /// <summary>Adapts a Kotlin <c>ObservableBooleanFlow</c> to <see cref="IObservable{T}"/>.</summary>
    public partial class SharedObservableBooleanFlow : IObservable<bool>
    {
        public IDisposable Subscribe(IObserver<bool> observer)
        {
            var cancel = Subscribe(
                value => observer.OnNext(value.BoolValue),
                throwable => observer.OnError(throwable.ToException()),
                observer.OnCompleted,
                // Deliver on Main: invoking the managed onNext callback from a Kotlin/Native
                // Default worker thread ties up that worker, and repeated subscriptions exhaust the
                // pool. The main thread is runtime-attached, so callbacks there are reliable (and a
                // UI app wants emissions on the UI thread).
                SharedDispatcher.Main
            );
            return new Disposable(() => cancel());
        }
    }
}
