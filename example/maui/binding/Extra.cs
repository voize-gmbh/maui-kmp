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
                throwable => tcs.TrySetException(throwable.ToException())
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
