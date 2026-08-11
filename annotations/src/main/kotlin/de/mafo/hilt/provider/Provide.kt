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
 * as usual. Multibindings are the exception, and go through [multibinding] instead:
 *
 * ```kotlin
 * @Provide(multibinding = IntoSet)
 * fun provideHomeEntry(): NavEntry = NavEntry("home")
 *
 * @Provide(multibinding = IntoMap)
 * @StringKey("login")
 * fun provideLoginHandler(): Handler = LoginHandler()
 * ```
 *
 * Dagger's own `@IntoSet`, `@IntoMap` and `@ElementsIntoSet` cannot be used here. Forwarding them
 * would work, but they would also stay on the annotated declaration, and dagger-compiler claims
 * those annotations wherever they appear: it looks for the enclosing type before reporting that
 * they are missing a `@Provides`, and a top-level declaration has none. The result is an internal
 * `IllegalStateException: No enclosing TypeElement` rather than any usable message, so the
 * processor rejects them. Map keys such as `@StringKey` are unaffected and forwarded as usual.
 *
 * @param into the Hilt component the generated module is installed in. Has to be a type annotated
 *   with `@DefineComponent`, which covers Hilt's built-in components as well as custom ones; the
 *   processor rejects anything else instead of letting Hilt fail later.
 * @param multibinding which Dagger multibinding annotation the generated `@Provides` function
 *   carries. Defaults to [Multibinding.None], an ordinary binding.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class Provide(
    val into: KClass<*> = SingletonComponent::class,
    val multibinding: Multibinding = Multibinding.None,
)
