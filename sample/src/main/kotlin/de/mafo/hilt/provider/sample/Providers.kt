package de.mafo.hilt.provider.sample

import de.mafo.hilt.provider.HiltProvider
import javax.inject.Singleton

data class Config(val baseUrl: String)

class ApiClient(val config: Config)

@HiltProvider
@Singleton
fun provideConfig(): Config = Config(baseUrl = "https://example.com")

@HiltProvider
fun provideApiClient(config: Config): ApiClient = ApiClient(config)
