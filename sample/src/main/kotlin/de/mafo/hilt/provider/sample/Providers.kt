package de.mafo.hilt.provider.sample

import dagger.hilt.components.SingletonComponent
import de.mafo.hilt.provider.Provide
import javax.inject.Singleton

data class Config(val baseUrl: String)

class ApiClient(val config: Config)

class Label(val text: String)

class LabelId(val value: Int)

fun createMyDependency() = ApiClient(Config("https://example.com"))

@Provide
@Singleton
fun provideConfig(): Config = Config(baseUrl = "https://example.com")

/** Inferred return type – the processor resolves it. */
@Provide
fun provideApiClient() = createMyDependency()

/** Explicit component, even though it is the default here. */
@Provide(into = SingletonComponent::class)
fun provideLabel(): Label = Label("label")

/**
 * Overload of [provideLabel]. Dagger rejects overloaded binding methods, so the generated
 * `@Provides` function is named `provideLabelBoolean`.
 */
@Provide
fun provideLabel(shortened: Boolean): LabelId = LabelId(if (shortened) 1 else 0)
