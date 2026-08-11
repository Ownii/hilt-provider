package de.mafo.hilt.provider.sample.android.feature

import de.mafo.hilt.provider.Multibinding.IntoSet
import de.mafo.hilt.provider.Provide

/**
 * One half of the multibinding – [DetailEntry] holds the other. They deliberately sit in separate
 * files, because the generated module is named after its file: this contributes to the same
 * `Set<NavEntry>` from two different modules, which is the case Hilt has to aggregate.
 */
@Provide(multibinding = IntoSet)
fun provideHomeEntry(): NavEntry = NavEntry("home")
