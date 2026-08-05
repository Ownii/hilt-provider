# hilt-provider

KSP-Plugin, das **Top-Level-Provider für Hilt** ermöglicht. Dagger verlangt, dass jede
`@Provides`-Methode in einem `@Module` liegt. Dieses Plugin generiert dieses Modul, sodass eine
einfache Top-Level-Funktion oder ein Top-Level-`val` als Binding ausreicht.

```kotlin
// Config.kt
@Provide
@Singleton
fun provideConfig(): Config = Config(baseUrl = "https://example.com")
```

wird zu

```kotlin
@Module
@InstallIn(SingletonComponent::class)
internal object Config_SingletonComponentModule {
  @Provides
  @Singleton
  public fun provideConfig(): Config = de.example.provideConfig()
}
```

Die Component ist optional — Default ist `SingletonComponent`, andernfalls
`@Provide(into = ViewModelComponent::class)`. Ein expliziter Rückgabetyp ist ebenfalls optional:
`@Provide fun provideDependency() = createMyDependency()`.

Top-Level-`val`s funktionieren genauso; die generierte `@Provides`-Funktion liest die Property:

```kotlin
@Provide
val defaultTimeoutSeconds = 30
```

## Namensschema der generierten Module

Ein Modul pro **Quelldatei und Component**, benannt nach der Datei:
`NavEntry.kt` → `NavEntry_SingletonComponentModule`.

Der Name kommt bewusst von der Datei und nicht von der Funktion. Ein Package kann keine zwei
Dateien gleichen Namens enthalten, sehr wohl aber mehrere Funktionen `provideNavEntry` — dasselbe
`@Provide fun provideNavEntry()` in zehn Feature-Packages ist damit konfliktfrei. Der
Component-Suffix ist nötig, weil `@InstallIn` am Modul steht: enthält eine Datei Provider für
mehrere Components, entstehen entsprechend mehrere Module.

Alle `@Provide`-Funktionen einer Datei landen im selben Modul. Bei **Overloads** wird die generierte
`@Provides`-Funktion nach ihren Parametertypen umbenannt (`provideNavEntry(id: String)` →
`provideNavEntryString`), weil Dagger überladene Binding-Methoden ablehnt: *"Cannot have more than
one binding method with the same name in a single module"*. Die Parametertypen als Suffix halten den
Namen stabil, wenn später ein weiterer Overload dazukommt — ein Index würde die Namen verschieben.
Der Delegate-Aufruf zeigt weiterhin auf die Originalfunktion.

## Module

| Modul          | Inhalt                                                                 |
|----------------|------------------------------------------------------------------------|
| `annotations`  | Öffentliche Annotation `@Provide` (JVM, `api`-Abhängigkeit `hilt-core`) |
| `processor`    | KSP-`SymbolProcessor` inkl. KotlinPoet-Generierung und Tests (kotlin-compile-testing) |
| `sample`       | Beispielmodul, das den Processor via `ksp(project(":processor"))` anwendet |

## Build

```bash
./gradlew build          # alles bauen + Tests
./gradlew :processor:test
./gradlew :sample:kspKotlin   # Generierung im Sample ausführen
```

Generierter Code des Samples: `sample/build/generated/ksp/main/kotlin/...`

## Aktueller Stand

Die Pipeline ist end-to-end verifiziert: Annotation → KSP-Processor → generiertes Hilt-Modul →
kompiliert im Sample.

Bereits umgesetzt:

- Top-Level-Funktionen und Top-Level-`val`s (inkl. `by lazy`)
- Generierung eines `internal object`-Moduls pro Quelldatei und Component (siehe Namensschema)
- Disambiguierung von Overloads über die Parametertypen
- `@InstallIn`-Component über den Annotationsparameter `into` (Default `SingletonComponent`)
- Weitergabe aller übrigen Annotationen (Scopes, Qualifier, Multibindings) an die `@Provides`-Methode
- Voll qualifizierter Delegate-Aufruf (verhindert Shadowing durch die generierte Funktion)
- Fehlermeldungen für alles, was nicht aufrufbar wäre: Member-Deklarationen, `private`,
  Extension-Funktionen und -Properties, `var`, `suspend`, Generics, Root-Package
- Fehlermeldung, wenn das Package den Modulnamen schon belegt
- Inferierte Rückgabetypen (`fun provideX() = createX()`)

Noch offen (Teil der API-Diskussion):

- Parametername `into` vs. `installIn` (Überschneidung mit `@IntoSet`/`@IntoMap`)
- Sichtbarkeit der generierten Module (aktuell `internal`)
- Unterstützung für `suspend`-Funktionen und Generics (aktuell abgelehnt)
- Kosmetik: KotlinPoet schreibt weitergegebene Annotationen als `@Named(`value` = "…")`
- Veröffentlichung (`maven-publish`), Android-Sample, CI

Der Dagger-Graph wird bewusst **nicht** getestet — das wäre ein Test von Dagger. Geprüft wird, dass
der generierte Code exakt der erwarteten Form entspricht und beim Aufruf an die annotierte Funktion
delegiert. Einmalig gegen `dagger-compiler` verifiziert wurde lediglich die Namensfrage bei
Overloads: ohne Umbenennung bricht Dagger ab, mit Umbenennung entstehen kollisionsfreie Factories.
