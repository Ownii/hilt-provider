package de.mafo.hilt.provider.sample

import dagger.multibindings.StringKey
import de.mafo.hilt.provider.Multibinding.ElementsIntoSet
import de.mafo.hilt.provider.Multibinding.IntoMap
import de.mafo.hilt.provider.Multibinding.IntoSet
import de.mafo.hilt.provider.Provide

class NavEntry(val route: String)

interface Handler

class LoginHandler : Handler

/** Dagger's own `@IntoSet` cannot go on a top-level declaration, hence the parameter. */
@Provide(multibinding = IntoSet)
fun provideHomeEntry(): NavEntry = NavEntry("home")

/** Contributes several elements at once, so the return type is the collection itself. */
@Provide(multibinding = ElementsIntoSet)
fun provideLegacyEntries(): Set<NavEntry> = setOf(NavEntry("legacy"), NavEntry("about"))

/** The map key stays an ordinary annotation and is forwarded next to the generated `@IntoMap`. */
@Provide(multibinding = IntoMap)
@StringKey("login")
fun provideLoginHandler(): Handler = LoginHandler()
