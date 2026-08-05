package de.mafo.hilt.provider.sample.android.feature

import de.mafo.hilt.provider.Provide
import javax.inject.Named
import javax.inject.Singleton

class Greeting(val text: String)

@Provide
@Singleton
fun provideGreeting(): Greeting = Greeting("Hello from a library module")

@Provide
@Named("build-flavour")
val buildFlavour: String = "sample"
