package de.voize.mauikmp.annotation

/**
 * Annotate a class, function, constructor or property to create a binding for it.
 *
 * @param canThrow Asserts whether this member can throw at runtime. Only meaningful on synchronous
 * **functions and constructors** on iOS — has no effect when placed on a class or property (KSP
 * emits a warning if you do). Default `true`: the member must also carry
 * `@Throws(Exception::class)` so Kotlin/Native bridges a thrown exception to a catchable `NSError`
 * (otherwise the build fails, see the `requireThrowsOnSyncBindings` KSP option). Set `canThrow = false`
 * to assert the member never throws — KSP then stops requiring `@Throws` and skips the throwing
 * machinery (no factory, no `[DisableDefaultCtor]`, plain `new SharedX()` keeps working).
 *
 * This is an assertion you are making, not a guard: if a `canThrow = false` member does throw at
 * runtime it terminates the process uncatchably (there is no `@Throws`/`NSError` bridge). Combining
 * `canThrow = false` with `@Throws` is contradictory and reported as an error.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.PROPERTY)
annotation class MauiBinding(
    val canThrow: Boolean = true,
)
