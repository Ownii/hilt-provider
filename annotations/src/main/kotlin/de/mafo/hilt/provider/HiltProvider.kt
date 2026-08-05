package de.mafo.hilt.provider

import dagger.hilt.components.SingletonComponent
import kotlin.reflect.KClass

/**
 * Marks a top-level function as a Hilt provider.
 *
 * Dagger requires every `@Provides` method to live inside a `@Module`. This annotation lifts that
 * restriction: the KSP processor generates the surrounding `@Module object` and delegates to the
 * annotated function.
 *
 * ```kotlin
 * @HiltProvider
 * @Singleton
 * fun provideHttpClient(config: Config): HttpClient = HttpClient(config)
 * ```
 *
 * Note: this is the placeholder shape used to wire up the build. The final API is still open for
 * discussion.
 *
 * @param component the Hilt component the generated module is installed in.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class HiltProvider(
    val component: KClass<*> = SingletonComponent::class,
)
