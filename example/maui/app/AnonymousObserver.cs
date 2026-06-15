namespace MauiKmpExample;

/// <summary>Small <see cref="IObserver{T}"/> built from lambdas, for subscribing to the Kotlin flows.</summary>
public sealed class AnonymousObserver<T> : IObserver<T>
{
    private readonly Action<T> _onNext;
    private readonly Action<Exception> _onError;
    private readonly Action _onCompleted;

    public AnonymousObserver(Action<T> onNext, Action<Exception>? onError = null, Action? onCompleted = null)
    {
        _onNext = onNext;
        _onError = onError ?? (_ => { });
        _onCompleted = onCompleted ?? (() => { });
    }

    public void OnNext(T value) => _onNext(value);
    public void OnError(Exception error) => _onError(error);
    public void OnCompleted() => _onCompleted();
}
