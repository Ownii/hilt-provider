package de.mafo.hilt.provider.sample

import dagger.hilt.components.SingletonComponent
import de.mafo.hilt.provider.Provide
import javax.inject.Singleton

data class Config(val baseUrl: String)

class ApiClient(val config: Config)

class RequestScopedThing

fun createMyDependency() = ApiClient(Config("https://example.com"))

@Provide
@Singleton
fun provideConfig(): Config = Config(baseUrl = "https://example.com")

/** Inferred return type – the processor resolves it. */
@Provide
fun provideApiClient() = createMyDependency()

/** Explicit component, even though it is the default here. */
@Provide(into = SingletonComponent::class)
fun provideRequestScopedThing(config: Config) = RequestScopedThing().also { println(config) }
