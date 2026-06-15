using Foundation;
using Voize;

namespace MauiKmpExample;

/// <summary>
/// Validation harness UI: one button per interop scenario from SDK-94. Each button calls into the
/// Kotlin/Native layer through the generated bindings and appends the observed result to the log.
/// Flow/Task callbacks arrive on a background thread, so everything that touches the UI is
/// marshalled back via the page <see cref="IDispatcher"/>.
/// </summary>
public class MainPage : ContentPage
{
    private readonly SharedInteropTest _interop = new();
    private readonly SharedDemoSdk _sdk = new();

    private readonly Label _log;

    private IDisposable? _intSub;
    private IDisposable? _dataSub;
    private IDisposable? _failingSub;
    private IDisposable? _stateSub;

    public MainPage()
    {
        Title = "maui-kmp interop";

        _log = new Label
        {
            Text = "Ready.\n",
            FontSize = 13,
            FontFamily = "Courier",
            LineBreakMode = LineBreakMode.WordWrap,
        };

        var buttons = new VerticalStackLayout
        {
            Padding = 16,
            Spacing = 8,
            Children =
            {
                Header("Suspend functions (Task<T>)"),
                Button("Run suspend function", RunSuspendAsync),
                Button("Failing suspend (caught)", RunFailingSuspendAsync),
                Button("Suspend nullable (null + value)", RunSuspendNullableAsync),

                Header("Flows (ObservableFlow<T> → IObservable)"),
                Button("Subscribe int flow", SubscribeIntFlow),
                Button("Unsubscribe int flow", () => Unsub(ref _intSub, "int flow")),
                Button("Subscribe data-class flow", SubscribeDataFlow),
                Button("Unsubscribe data-class flow", () => Unsub(ref _dataSub, "data flow")),
                Button("Trigger failing flow", SubscribeFailingFlow),

                Header("Lifecycle + threading"),
                Button("SDK start", () => { _sdk.Start(); Log("called start()"); }),
                Button("SDK stop", () => { _sdk.Stop(); Log("called stop()"); }),
                Button("SDK restart", () => { _sdk.Restart(); Log("called restart()"); }),
                Button("Background work (off UI thread)", RunBackgroundWorkAsync),

                Header("Callbacks / lambdas"),
                Button("Pass C# callback", PassCallback),
                Button("Parameterized callback (x5)", PassParameterizedCallback),

                Header("Nullable + errors"),
                Button("Nullable roundtrip", NullableRoundtrip),
                Button("Stream stress test (1000x)", RunStressTestAsync),
                Button("Sync throw (⚠ may terminate)", SyncThrow),
            },
        };

        var logPanel = new Border
        {
            Stroke = Colors.LightGray,
            StrokeThickness = 1,
            Padding = 8,
            Content = new ScrollView { Content = _log },
        };

        var buttonsScroll = new ScrollView { Content = buttons };
        Grid.SetRow(buttonsScroll, 0);
        Grid.SetRow(logPanel, 1);

        var grid = new Grid
        {
            RowDefinitions = new RowDefinitionCollection
            {
                new RowDefinition { Height = GridLength.Star },
                new RowDefinition { Height = new GridLength(220) },
            },
            Children = { buttonsScroll, logPanel },
        };

        Content = grid;

        // Observe SDK started state for the whole session (state flow: replays latest value).
        _stateSub = _sdk.StartedState().Subscribe(new AnonymousObserver<bool>(
            value => OnUi(() => Log($"startedState -> {value}")),
            error => OnUi(() => Log($"startedState error: {error.Message}"))));

        // Automated smoke test: exercise the key interop paths once on launch and log to Console
        // (visible via `xcrun simctl launch --console`). Acts as a quick end-to-end sanity check.
        _ = RunSmokeTestAsync();
    }

    private async Task RunSmokeTestAsync()
    {
        void Smoke(string m) => Console.WriteLine($"SMOKE: {m}");
        try
        {
            Smoke("begin");

            var s = await _interop.SuspendString().ToStringTask();
            Smoke($"suspendString -> \"{s}\"");

            var echo = await _interop.SuspendEcho("ping").ToStringTask();
            Smoke($"suspendEcho -> \"{echo}\"");

            var n1 = await _interop.SuspendNullableString(true).ToStringTask();
            var n2 = await _interop.SuspendNullableString(false).ToStringTask();
            Smoke($"suspendNullable -> [{(n1 is null ? "null" : n1)}, {(n2 is null ? "null" : n2)}]");

            try { await _interop.FailingSuspend().ToStringTask(); Smoke("failingSuspend DID NOT throw (unexpected)"); }
            catch (Exception ex) { Smoke($"failingSuspend caught -> {ex.Message}"); }

            _interop.WithCallback(v => Smoke($"callback -> \"{v}\""));
            _interop.WithParameterizedCallback(3, i => Smoke($"onEach -> {i.Int32Value}"));

            Smoke($"nullableRoundtrip(null) -> {(_interop.NullableRoundtrip(null) is null ? "null" : "?")}");
            Smoke($"nullableRoundtrip(\"x\") -> \"{_interop.NullableRoundtrip("x")}\"");

            // Subscribe to the fast int flow briefly, then cancel. Run off the main thread.
            var received = 0;
            Smoke("subscribing fastEvents…");
            await Task.Run(async () =>
            {
                var sub = _interop.FastEvents().Subscribe(new AnonymousObserver<NSObject?>(
                    v => { received++; if (received <= 3) Smoke($"fastEvents -> {(v as NSNumber)?.Int32Value} (thread {Environment.CurrentManagedThreadId})"); },
                    e => Smoke($"fastEvents onError -> {e.Message}"),
                    () => Smoke("fastEvents onCompleted")));
                Smoke($"fastEvents subscribed, cancel handle null? {sub is null}");
                await Task.Delay(1500).ConfigureAwait(false);
                sub?.Dispose();
            });
            Smoke($"fastEvents cancelled after {received} emissions");

            _sdk.Start();
            _sdk.Stop();
            var bg = await _sdk.DoBackgroundWork().ToStringTask();
            Smoke($"doBackgroundWork -> \"{bg}\"");

            Smoke("end OK");
        }
        catch (Exception ex)
        {
            Smoke($"FAILED: {ex}");
        }
    }

    // --- Suspend ---

    private async void RunSuspendAsync()
    {
        Log("suspendString… (1s)");
        var result = await _interop.SuspendString().ToStringTask();
        Log($"suspendString -> \"{result}\"");
    }

    private async void RunFailingSuspendAsync()
    {
        Log("failingSuspend… (0.3s)");
        try
        {
            await _interop.FailingSuspend().ToStringTask();
            Log("failingSuspend returned (unexpected)");
        }
        catch (Exception ex)
        {
            Log($"failingSuspend caught: {ex.Message}");
        }
    }

    private async void RunSuspendNullableAsync()
    {
        var isNull = await _interop.SuspendNullableString(true).ToStringTask();
        var notNull = await _interop.SuspendNullableString(false).ToStringTask();
        Log($"suspendNullable(true) -> {(isNull is null ? "null" : $"\"{isNull}\"")}");
        Log($"suspendNullable(false) -> {(notNull is null ? "null" : $"\"{notNull}\"")}");
    }

    // --- Flows ---

    private void SubscribeIntFlow()
    {
        if (_intSub is not null) { Log("int flow already subscribed"); return; }
        Log("subscribing int flow…");
        _intSub = _interop.IntEvents().Subscribe(new AnonymousObserver<NSObject?>(
            value => OnUi(() => Log($"intEvents: {(value as NSNumber)?.Int32Value}")),
            error => OnUi(() => Log($"intEvents error: {error.Message}")),
            () => OnUi(() => Log("intEvents completed"))));
    }

    private void SubscribeDataFlow()
    {
        if (_dataSub is not null) { Log("data flow already subscribed"); return; }
        Log("subscribing data-class flow…");
        _dataSub = _interop.DataClassEvents().Subscribe(new AnonymousObserver<NSObject?>(
            value =>
            {
                var data = value as SharedDemoData;
                OnUi(() => Log($"dataClassEvents: id={data?.Id} label={data?.Label} nested={data?.Nested?.Value}"));
            },
            error => OnUi(() => Log($"dataClassEvents error: {error.Message}"))));
    }

    private void SubscribeFailingFlow()
    {
        _failingSub?.Dispose();
        Log("subscribing failing flow…");
        _failingSub = _interop.FailingEvents().Subscribe(new AnonymousObserver<NSObject?>(
            value => OnUi(() => Log($"failingEvents: {(value as NSNumber)?.Int32Value}")),
            error => OnUi(() => Log($"failingEvents onError: {error.Message}")),
            () => OnUi(() => Log("failingEvents completed"))));
    }

    private void Unsub(ref IDisposable? sub, string name)
    {
        if (sub is null) { Log($"{name} not subscribed"); return; }
        sub.Dispose();
        sub = null;
        Log($"unsubscribed {name}");
    }

    // --- Lifecycle / threading ---

    private async void RunBackgroundWorkAsync()
    {
        Log("doBackgroundWork… (0.5s, off UI thread)");
        var result = await _sdk.DoBackgroundWork().ToStringTask();
        Log($"doBackgroundWork -> \"{result}\"");
    }

    // --- Callbacks ---

    private void PassCallback()
    {
        _interop.WithCallback(value => OnUi(() => Log($"callback invoked: \"{value}\"")));
    }

    private void PassParameterizedCallback()
    {
        _interop.WithParameterizedCallback(5, n => OnUi(() => Log($"onEach: {n.Int32Value}")));
    }

    // --- Nullable + errors ---

    private void NullableRoundtrip()
    {
        var a = _interop.NullableRoundtrip(null);
        var b = _interop.NullableRoundtrip("hello");
        Log($"nullableRoundtrip(null) -> {(a is null ? "null" : $"\"{a}\"")}");
        Log($"nullableRoundtrip(\"hello\") -> {(b is null ? "null" : $"\"{b}\"")}");
    }

    private async void RunStressTestAsync()
    {
        const int iterations = 1000;
        Log($"stress test: {iterations} subscribe/cancel cycles…");
        await Task.Run(() =>
        {
            for (var i = 0; i < iterations; i++)
            {
                var sub = _interop.FastEvents().Subscribe(
                    new AnonymousObserver<NSObject?>(_ => { }));
                sub.Dispose();
                if (i % 100 == 0)
                {
                    var done = i;
                    OnUi(() => Log($"stress running {done}/{iterations}"));
                }
            }
            OnUi(() => Log($"stress test done ({iterations} cycles, no crash)"));
        });
    }

    private void SyncThrow()
    {
        // NOTE: a synchronous Kotlin exception crossing the ObjC boundary in a non-@Throws function
        // terminates the process under Kotlin/Native — it is NOT a catchable managed exception.
        // This button exists to demonstrate exactly that, and why async errors must go through the
        // Task/ObservableFlow callbacks (which deliver Throwable to onError instead of throwing).
        Log("calling throwError() — expect process termination…");
        try
        {
            _interop.ThrowError();
            Log("throwError returned (unexpected)");
        }
        catch (Exception ex)
        {
            Log($"throwError caught: {ex.Message}");
        }
    }

    // --- helpers ---

    private void OnUi(Action action) => Dispatcher.Dispatch(action);

    private void Log(string message)
    {
        // Always marshal to UI; callers may be on a background thread.
        Dispatcher.Dispatch(() => _log.Text = $"{message}\n{_log.Text}");
    }

    private static Label Header(string text) => new()
    {
        Text = text,
        FontSize = 15,
        FontAttributes = FontAttributes.Bold,
        Margin = new Thickness(0, 12, 0, 0),
    };

    private static Button Button(string text, Action onClick)
    {
        var button = new Button { Text = text };
        button.Clicked += (_, _) => onClick();
        return button;
    }
}
