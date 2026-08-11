package de.mafo.hilt.provider

/**
 * How a [Provide] declaration contributes to a multibinding.
 *
 * Each entry stands for the Dagger annotation of the same name, which the processor puts on the
 * generated `@Provides` function. Naming it here rather than annotating the declaration directly is
 * not a matter of taste: `@IntoSet` and friends on a top-level declaration make dagger-compiler
 * abort with an internal `IllegalStateException: No enclosing TypeElement`, see [Provide].
 *
 * ```kotlin
 * @Provide(multibinding = IntoSet)
 * fun provideHomeEntry(): NavEntry = NavEntry("home")
 * ```
 */
public enum class Multibinding {
    /** An ordinary binding. */
    None,

    /** Contributes the returned value as one element of a `Set`, like `@IntoSet`. */
    IntoSet,

    /** Contributes the returned collection as several elements of a `Set`, like `@ElementsIntoSet`. */
    ElementsIntoSet,

    /**
     * Contributes the returned value as one entry of a `Map`, like `@IntoMap`. Requires a map key
     * annotation such as `@StringKey` on the declaration, which is forwarded like any other.
     */
    IntoMap,
}
