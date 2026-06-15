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
    // Constructed via the generated factories: both classes' constructors are annotated
    // @Throws(Exception::class), so the raw binding is `Constructor(out NSError)` rather than a
    // parameterless `new()`. CreateX() hides the out-param and would throw NSErrorException on a
    // failing init (these don't fail; ThrowingCtor's button exercises the failing path).
    private readonly SharedInteropTest _interop = VoizeSdk.CreateSharedInteropTest();
    private readonly SharedDemoSdk _sdk = VoizeSdk.CreateSharedDemoSdk();
    // `Test` is a @Serializable data class with required, non-null fields. Its binding carries
    // [DisableDefaultCtor], so `new SharedTest()` does NOT compile — we must pass every field. This
    // is the DisableDefaultCtor-for-data-classes fix: it prevents constructing a half-initialized
    // object whose non-null Kotlin fields would be null at runtime.
    private readonly SharedE2ETest _e2e = VoizeSdk.CreateSharedE2ETest(MakeTest());

    private static SharedTest MakeTest() => new SharedTest(
        "smoke",
        new[] { new SharedTestNested("nested", 1) },
        new NSDictionary<NSString, SharedTestNested>(),
        42L,
        (byte)1);


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

                Header("Sync error propagation"),
                Button("throwError (catchable via @Throws)", SyncThrow),
                Button("throwFatalError (⚠ terminates process)", SyncFatalThrow),
                Button("ThrowingCtor (catchable ctor)", SyncThrowingCtor),

                Header("Construction paths"),
                Button("Factory: CreateSharedInteropTest()", ConstructViaFactory),
                Button("Deprecated shim: new SharedInteropTest()", ConstructViaDeprecatedShim),
                Button("Companion: getInstance()", ConstructViaCompanion),
                Button("Companion: init(\"\") (catchable throw)", ConstructViaCompanionThrowing),

                Header("Regression tests"),
                Button("Typealias callback NSError position", RegressionTypeAliasCallback),
                Button("Block-first ctor baseName (initAndReturn…)", RegressionBlockFirstCtor),

                Header("kotlin.time (Instant + Clock)"),
                Button("Instant roundtrip (non-null)", KotlinTimeInstantRoundtrip),
                Button("Instant roundtrip (nullable: null + value)", KotlinTimeInstantNullableRoundtrip),
                Button("Clock.System.now() (convenience)", KotlinTimeClockSystemNow),
                Button("nowFromClock(systemClock())", KotlinTimeClockViaProtocol),

                Header("Data class ctor (DisableDefaultCtor)"),
                Button("Build InstantData(label, Clock.System.now())", BuildInstantData),
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

        // Subscribe to the async-error publisher (ErrorBus, the C# SharedFlow equivalent).
        // Every Exception the Task/ObservableFlow adapters surface is broadcast here; Error and
        // CancellationException are never published. Replaces the old global MauiKmp.onError sink.
        var busCount = 0;
        using var errorBusSub = ErrorBus.Errors.Subscribe(
            new AnonymousObserver<AdapterError>(e => Interlocked.Increment(ref busCount)));

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
            Smoke($"bus count after failingSuspend = {busCount} (expect >= 1)");

            // Failing flow: the ObservableFlow adapter publishes the error to the bus too.
            await Task.Run(async () =>
            {
                var before = busCount;
                var done = new TaskCompletionSource<bool>();
                var sub = _interop.FailingEvents().Subscribe(new AnonymousObserver<NSObject?>(
                    _ => { },
                    _ => done.TrySetResult(true),
                    () => done.TrySetResult(true)));
                await Task.WhenAny(done.Task, Task.Delay(3000)).ConfigureAwait(false);
                sub?.Dispose();
                Smoke($"bus delta after failingEvents = {busCount - before} (expect >= 1)");
            });

            _interop.WithCallback(v => Smoke($"callback -> \"{v}\""));
            _interop.WithParameterizedCallback(3, i => Smoke($"onEach -> {i.Int32Value}"));

            Smoke($"nullableRoundtrip(null) -> {(_interop.NullableRoundtrip(null) is null ? "null" : "?")}");
            Smoke($"nullableRoundtrip(\"x\") -> \"{_interop.NullableRoundtrip("x")}\"");

            // Cancellation contract (the C# side of ObservableFlowCancellationTest): disposing a
            // subscription cancels the Kotlin job. The resulting CancellationException must NEVER be
            // surfaced as an error — not to the per-subscription onError, nor to the global ErrorBus —
            // and emissions must stop. Subscribe to the fast int flow, cancel, then assert all three.
            var received = 0;
            var errors = 0;
            var busBeforeCancel = busCount;
            Smoke("subscribing fastEvents…");
            await Task.Run(async () =>
            {
                var sub = _interop.FastEvents().Subscribe(new AnonymousObserver<NSObject?>(
                    v => { received++; if (received <= 3) Smoke($"fastEvents -> {(v as NSNumber)?.Int32Value} (thread {Environment.CurrentManagedThreadId})"); },
                    e => { Interlocked.Increment(ref errors); Smoke($"fastEvents onError -> {e.Message}"); },
                    () => Smoke("fastEvents onCompleted")));
                Smoke($"fastEvents subscribed, cancel handle null? {sub is null}");
                await Task.Delay(1500).ConfigureAwait(false);
                sub?.Dispose();
                // Give any in-flight emission/cancellation a moment to settle, then prove it stopped.
                var receivedAtCancel = received;
                await Task.Delay(500).ConfigureAwait(false);
                Smoke($"fastEvents emissions after cancel = {received - receivedAtCancel} (expect 0 — collection stopped)");
            });
            Smoke($"fastEvents cancelled after {received} emissions");
            Smoke($"fastEvents onError count = {errors} (expect 0 — cancellation is not an error)");
            Smoke($"bus delta after cancel = {busCount - busBeforeCancel} (expect 0 — cancellation never published)");

            _sdk.Start();
            _sdk.Stop();
            var bg = await _sdk.DoBackgroundWork().ToStringTask();
            Smoke($"doBackgroundWork -> \"{bg}\"");

            // Throws-bridged sync errors must be catchable (wrappers hide out NSError).
            try { _interop.ThrowError(); Smoke("throwError DID NOT throw (unexpected)"); }
            catch (NSErrorException ex) { Smoke($"throwError caught -> {ex.Error.LocalizedDescription}"); }
            catch (Exception ex) { Smoke($"throwError WRONG exception type {ex.GetType().Name} -> {ex.Message}"); }

            try { using var _ = VoizeSdk.CreateSharedThrowingCtor(); Smoke("ThrowingCtor DID NOT throw (unexpected)"); }
            catch (NSErrorException ex) { Smoke($"ThrowingCtor caught -> {ex.Error.LocalizedDescription}"); }
            catch (Exception ex) { Smoke($"ThrowingCtor WRONG exception type {ex.GetType().Name} -> {ex.Message}"); }

            // Construction paths — all three must work.
            // (a) deprecated shim: `new SharedX()` compiles (with [Obsolete]) and runs the real ctor.
#pragma warning disable CS0618
            var shimInterop = new SharedInteropTest();
#pragma warning restore CS0618
            Smoke($"shim ctor ran -> nullableRoundtrip(\"x\")=\"{shimInterop.NullableRoundtrip("x")}\"");

            // (b) companion factory: no `new`, no wrapper prefix; success case.
            var comp = SharedCompanionTest.Companion.GetInstance();
            Smoke($"companion getInstance() -> getValue()=\"{comp.GetValue()}\"");
            var comp2 = SharedCompanionTest.Companion.Init("hello");
            Smoke($"companion init(\"hello\") -> getValue()=\"{comp2.GetValue()}\"");

            // (c) companion factory, throwing case → catchable NSErrorException, not a crash.
            try { SharedCompanionTest.Companion.Init(""); Smoke("companion init(\"\") DID NOT throw (unexpected)"); }
            catch (NSErrorException ex) { Smoke($"companion init(\"\") caught -> {ex.Error.LocalizedDescription}"); }

            // Regression: typealias callback — NSError must be placed before the block (not at end).
            // If the selector were wrong ("unrecognized selector") this would throw/crash before the
            // lambda fires. The fact that onResult is invoked proves the selector matched K/N's output.
            using var aliasTest = VoizeSdk.CreateSharedTypeAliasCallbackTest();
            string? aliasResult = null;
            aliasTest.WithAliasCallback(s => aliasResult = s?.ToString());
            Smoke($"typealias callback -> \"{aliasResult}\" (expect: called via typealias callback)");

            // Regression: block-first constructor — baseName must be `init`, not `initWith`, giving
            // selector `initAndReturnError:onReady:`. A wrong selector would crash at ctor call time.
            var onReadyCalled = false;
            using var blockCtorTest = VoizeSdk.CreateSharedBlockFirstCtorTest(() => onReadyCalled = true);
            Smoke($"block-first ctor onReady called = {onReadyCalled} (expect: True)");
            Smoke($"block-first ctor ping = \"{blockCtorTest.Ping()}\" (expect: pong)");

            // Binding WITHOUT @Throws handling (canThrow = false): no NSError out-param, no factory,
            // no wrapper. Construction is the plain `new SharedX(...)` and the method is called
            // directly (contrast with the @Throws path above, which goes through VoizeSdk + try/catch).
            var plainValue = new SharedNonThrowingValue("hello");
            Smoke($"NonThrowingValue (no @Throws) -> getValue()=\"{plainValue.GetValue()}\" (expect: hello)");

            // kotlin.time.Instant binding: verifies the KSP type mapping
            // `kotlin.time.Instant` → `SharedKotlinInstant` is correct end-to-end.
            // Round-trip: create an Instant at a known epoch-ms, pass it through the Kotlin boundary,
            // read back the epoch-ms. Any type or selector mismatch would crash before we get here.
            const long epochMs = 1_700_000_000_000L; // 2023-11-14T22:13:20Z
            var kotlinInstant = SharedKotlinInstant.Companion.FromEpochMilliseconds(epochMs);
            var roundtripped = _e2e.TestKotlinTimeInstant(kotlinInstant);
            Smoke($"kotlin.time.Instant roundtrip epochMs={roundtripped.ToEpochMilliseconds()} (expect: {epochMs})");

            var nullResult = _e2e.TestKotlinTimeInstantNullable(null);
            var nonNullResult = _e2e.TestKotlinTimeInstantNullable(kotlinInstant);
            Smoke($"kotlin.time.Instant nullable(null)={(nullResult is null ? "null" : "?")} (expect: null)");
            Smoke($"kotlin.time.Instant nullable(value)={nonNullResult?.ToEpochMilliseconds()} (expect: {epochMs})");

            // kotlin.time.Clock: both the convenience wrapper and the protocol path.
            var sysNow = _e2e.ClockSystemNow();
            Smoke($"clockSystemNow() epochMs={sysNow.ToEpochMilliseconds()} (should be > {epochMs})");
            var clock = _e2e.SystemClock();
            var clockNow = _e2e.NowFromClock(clock);
            Smoke($"nowFromClock(systemClock()) epochMs={clockNow.ToEpochMilliseconds()} (should be > {epochMs})");

            // Data class with DisableDefaultCtor: a non-null kotlin.time.Instant is required to
            // construct it (`new SharedInstantData()` would not compile). Round-trip and read the
            // non-null fields back to prove they survived (no half-initialized null fields).
            var instantData = new SharedInstantData("smoke-event", kotlinInstant);
            var echoedData = _e2e.EchoInstantData(instantData);
            Smoke($"InstantData label=\"{echoedData.Label}\" (expect: smoke-event)");
            Smoke($"InstantData timestamp epochMs={echoedData.Timestamp.ToEpochMilliseconds()} (expect: {epochMs})");

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
        // Uses the generated VoizeSdk extension: the raw binding's `out NSError`
        // is hidden and surfaces as a catchable NSErrorException — the original call shape.
        Log("calling throwError() — expecting a catchable error…");
        try
        {
            _interop.ThrowError();
            Log("throwError returned without error (unexpected)");
        }
        catch (NSErrorException ex)
        {
            Log($"throwError caught (not a crash): {ex.Error.LocalizedDescription}");
        }
    }

    private void SyncFatalThrow()
    {
        Log("calling throwFatalError() — THIS WILL TERMINATE THE PROCESS…");
        _interop.ThrowFatalError();
    }

    private void SyncThrowingCtor()
    {
        // Uses the generated factory: constructors cannot be extension-wrapped, so throwing
        // constructors get a static Create<Type>() that hides the `out NSError`.
        Log("calling CreateSharedThrowingCtor() — expecting a catchable error…");
        try
        {
            var _ = VoizeSdk.CreateSharedThrowingCtor();
            Log("ThrowingCtor returned without error (unexpected)");
        }
        catch (NSErrorException ex)
        {
            Log($"ThrowingCtor caught (not a crash): {ex.Error.LocalizedDescription}");
        }
    }

    // --- construction paths  ---

    private void ConstructViaFactory()
    {
        // Recommended path for a @Throws constructor: the generated factory hides the `out NSError`
        // and would throw a catchable NSErrorException on a failing init.
        var interop = VoizeSdk.CreateSharedInteropTest();
        Log($"factory ctor ran -> nullableRoundtrip(\"x\") = \"{interop.NullableRoundtrip("x")}\"");
    }

    private void ConstructViaDeprecatedShim()
    {
        // Compatibility path (emitDeprecatedConstructorShims=true): the old `new SharedX()` still
        // compiles — with an [Obsolete] warning — and runs the real Kotlin ctor via the throwing
        // initializer. Suppress the warning locally; in real code it nudges you to migrate.
        Log("constructing via deprecated new SharedInteropTest() shim…");
#pragma warning disable CS0618
        var interop = new SharedInteropTest();
#pragma warning restore CS0618
        Log($"shim ctor ran -> nullableRoundtrip(\"x\") = \"{interop.NullableRoundtrip("x")}\"");
    }

    private void ConstructViaCompanion()
    {
        // Companion factory path: no `new`, no wrapper-class prefix. @Throws so it is catchable;
        // here it succeeds.
        Log("calling SharedCompanionTest.Companion.GetInstance()…");
        var inst = SharedCompanionTest.Companion.GetInstance();
        Log($"getInstance() -> getValue() = \"{inst.GetValue()}\"");
    }

    private void ConstructViaCompanionThrowing()
    {
        // Same companion path, throwing case: init("") fails in Kotlin and surfaces as a catchable
        // NSErrorException (via the @Throws → NSError bridge), not a process crash.
        Log("calling SharedCompanionTest.Companion.Init(\"\") — expecting a catchable error…");
        try
        {
            var inst = SharedCompanionTest.Companion.Init("");
            Log($"init(\"\") returned without error (unexpected): {inst.GetValue()}");
        }
        catch (NSErrorException ex)
        {
            Log($"init(\"\") caught (not a crash): {ex.Error.LocalizedDescription}");
        }
    }

    // --- Regression tests  ---

    private void RegressionTypeAliasCallback()
    {
        // Regression: typealias-callback NSError placement (errorParameterIndex typealias expansion).
        // `withAliasCallback(onResult: OnStringResult)` where OnStringResult = (String)->Unit.
        // K/N places NSError BEFORE the block → selector = `withAliasCallbackAndReturnError:onResult:`.
        // Before the fix, KSP didn't expand the alias, misidentified the param as non-block, and placed
        // NSError at the end → selector mismatch → "unrecognized selector" crash at the call site.
        Log("calling withAliasCallback (typealias expansion regression)…");
        using var test = VoizeSdk.CreateSharedTypeAliasCallbackTest();
        string? received = null;
        test.WithAliasCallback(s => received = s?.ToString());
        Log($"withAliasCallback -> \"{received}\"  (expect: called via typealias callback)");
    }

    private void RegressionBlockFirstCtor()
    {
        // Regression: block-first constructor baseName (initAndReturn vs initWithAndReturn).
        // `constructor(onReady: () -> Unit)` — first param is a block, so errorPos == 0.
        // K/N selector = `initAndReturnError:onReady:` (baseName = "init", not "initWith").
        // Before the fix, KSP emitted "initWith" → bgen couldn't match the selector → startup crash.
        Log("calling CreateSharedBlockFirstCtorTest (block-first ctor regression)…");
        var onReadyCalled = false;
        using var test = VoizeSdk.CreateSharedBlockFirstCtorTest(() => onReadyCalled = true);
        Log($"onReady called = {onReadyCalled}  (expect: True)");
        Log($"ping() = \"{test.Ping()}\"  (expect: pong)");
    }

    // --- kotlin.time.Instant tests ---

    private void KotlinTimeInstantRoundtrip()
    {
        // kotlin.time.Instant (stdlib) is distinct from kotlinx.datetime.Instant.
        // KSP maps it to SharedKotlinInstant. If the ObjC type name or selector is wrong
        // this crashes before returning.
        const long epochMs = 1_700_000_000_000L;
        var instant = SharedKotlinInstant.Companion.FromEpochMilliseconds(epochMs);
        var result = _e2e.TestKotlinTimeInstant(instant);
        Log($"kotlin.time.Instant roundtrip -> {result.ToEpochMilliseconds()} (expect: {epochMs})");
    }

    private void KotlinTimeInstantNullableRoundtrip()
    {
        const long epochMs = 1_700_000_000_000L;
        var instant = SharedKotlinInstant.Companion.FromEpochMilliseconds(epochMs);
        var nullResult = _e2e.TestKotlinTimeInstantNullable(null);
        var nonNullResult = _e2e.TestKotlinTimeInstantNullable(instant);
        Log($"TestKotlinTimeInstantNullable(null) -> {(nullResult is null ? "null" : "?")}  (expect: null)");
        Log($"TestKotlinTimeInstantNullable(value) -> {nonNullResult?.ToEpochMilliseconds()}  (expect: {epochMs})");
    }

    private void KotlinTimeClockSystemNow()
    {
        // Convenience wrapper: calls Clock.System.now() on the Kotlin side and returns the
        // result as SharedKotlinInstant. Verifies the clockSystemNow() binding compiles and runs.
        var now = _e2e.ClockSystemNow();
        Log($"clockSystemNow() -> epochMs={now.ToEpochMilliseconds()} (should be a recent timestamp)");
    }

    private void KotlinTimeClockViaProtocol()
    {
        // Exercises the kotlin.time.Clock protocol binding end-to-end:
        // systemClock() returns ISharedKotlinClock (Clock.System), nowFromClock() calls now() on it.
        var clock = _e2e.SystemClock();
        var now = _e2e.NowFromClock(clock);
        Log($"nowFromClock(systemClock()) -> epochMs={now.ToEpochMilliseconds()}");
    }

    private void BuildInstantData()
    {
        // DisableDefaultCtor fix end-to-end: SharedInstantData is a data class with required,
        // non-null fields (label, timestamp), so `new SharedInstantData()` does NOT compile — the
        // designated ctor must be used. We build one with a real kotlin.time.Instant (now) and
        // round-trip it through echoInstantData, then read the non-null fields back.
        var now = _e2e.ClockSystemNow();
        var data = new SharedInstantData("button-event", now);
        var echoed = _e2e.EchoInstantData(data);
        Log($"InstantData(label=\"{echoed.Label}\", timestamp epochMs={echoed.Timestamp.ToEpochMilliseconds()})");
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
