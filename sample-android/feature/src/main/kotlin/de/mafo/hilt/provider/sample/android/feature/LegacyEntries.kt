package de.mafo.hilt.provider.sample.android.feature

import de.mafo.hilt.provider.Multibinding.ElementsIntoSet
import de.mafo.hilt.provider.Provide

/**
 * Merges into the same `Set<NavEntry>` as [provideHomeEntry], but contributes two elements at once.
 * The generic return type is the reason this is here and not only in the JVM sample: only
 * dagger-compiler can confirm that Kotlin does not turn it into `Set<? extends NavEntry>`.
 */
@Provide(multibinding = ElementsIntoSet)
fun provideLegacyEntries(): Set<NavEntry> = setOf(NavEntry("legacy"), NavEntry("about"))
