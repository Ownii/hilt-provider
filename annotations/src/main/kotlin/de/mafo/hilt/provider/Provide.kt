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
 * Scopes, qualifiers and multibinding annotations are forwarded to the generated `@Provides`
 * function, so they keep working as usual.
 *
 * @param into the Hilt component the generated module is installed in.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class Provide(
    val into: KClass<*> = SingletonComponent::class,
)
