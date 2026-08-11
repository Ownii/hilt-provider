package de.mafo.hilt.provider.sample.android.feature

import dagger.multibindings.StringKey
import de.mafo.hilt.provider.Multibinding.IntoMap
import de.mafo.hilt.provider.Provide

interface Handler

class LoginHandler : Handler

class LogoutHandler : Handler

/**
 * The map key is a plain forwarded annotation; only `@IntoMap` itself has to come from the
 * parameter. Two entries in one file, so both end up in the same generated module.
 */
@Provide(multibinding = IntoMap)
@StringKey("login")
fun provideLoginHandler(): Handler = LoginHandler()

@Provide(multibinding = IntoMap)
@StringKey("logout")
fun provideLogoutHandler(): Handler = LogoutHandler()
