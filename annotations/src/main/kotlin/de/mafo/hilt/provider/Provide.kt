package de.mafo.hilt.provider

import dagger.hilt.components.SingletonComponent
import kotlin.reflect.KClass

/**
 * Marks a top-level function or property as a Hilt provider.
 *
 * Dagger requires every `@Provides` method to live inside a `@Module`. This annotation lifts that
 * restriction: the KSP processor generates the surrounding `@Module object` and delegates to the
 * annotated declaration.
 *
 * ```kotlin
 * @Provide
 * @Singleton
 * fun provideDependency() = createMyDependency()
 *
 * @Provide
 * val defaultConfig = Config(baseUrl = "https://example.com")
 * ```
 *
 * Scopes and qualifiers are forwarded to the generated `@Provides` function, so they keep working
 * as usual. Multibindings are the exception: Dagger cannot cope with `@IntoSet`, `@IntoMap` or
 * `@ElementsIntoSet` on a top-level declaration, so the processor rejects that combination instead
 * of letting dagger-compiler fail. Map keys such as `@StringKey` are unaffected.
 *
 * @param into the Hilt component the generated module is installed in. Has to be a type annotated
 *   with `@DefineComponent`, which covers Hilt's built-in components as well as custom ones; the
 *   processor rejects anything else instead of letting Hilt fail later.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class Provide(
    val into: KClass<*> = SingletonComponent::class,
)
