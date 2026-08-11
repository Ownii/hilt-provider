package de.mafo.hilt.provider.sample.android.feature

import de.mafo.hilt.provider.Multibinding.IntoSet
import de.mafo.hilt.provider.Provide

/** The second contribution to the `Set<NavEntry>`, see [provideHomeEntry]. */
@Provide(multibinding = IntoSet)
fun provideDetailEntry(): NavEntry = NavEntry("detail")
