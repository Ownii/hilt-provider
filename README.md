# hilt-provider

KSP-Plugin, das **Top-Level-Provider für Hilt** ermöglicht. Dagger verlangt, dass jede
`@Provides`-Methode in einem `@Module` liegt. Dieses Plugin generiert dieses Modul, sodass eine
einfache Top-Level-Funktion als Binding ausreicht.

```kotlin
@Provide
@Singleton
fun provideConfig(): Config = Config(baseUrl = "https://example.com")
```

wird zu

```kotlin
@Module
@InstallIn(SingletonComponent::class)
internal object ProvideConfigHiltModule {
  @Provides
  @Singleton
  public fun provideConfig(): Config = de.example.provideConfig()
}
```

Die Component ist optional — Default ist `SingletonComponent`, andernfalls
`@Provide(into = ViewModelComponent::class)`. Ein expliziter Rückgabetyp ist ebenfalls optional:
`@Provide fun provideDependency() = createMyDependency()`.

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

- Generierung eines `internal object`-Moduls pro annotierter Top-Level-Funktion
- `@InstallIn`-Component über den Annotationsparameter `into` (Default `SingletonComponent`)
- Weitergabe aller übrigen Annotationen (Scopes, Qualifier, Multibindings) an die `@Provides`-Methode
- Voll qualifizierter Delegate-Aufruf (verhindert Shadowing durch die generierte Funktion)
- Fehlermeldungen für Member-Funktionen, `suspend`, generische Funktionen und Root-Package
- Inferierte Rückgabetypen (`fun provideX() = createX()`)

Noch offen (Teil der API-Diskussion):

- Parametername `into` vs. `installIn` (Überschneidung mit `@IntoSet`/`@IntoMap`)
- Namensschema und Sichtbarkeit der generierten Module, Verhalten bei Overloads
- Unterstützung für Top-Level-`val`s, `suspend`-Funktionen, Generics
- Anbindung des Hilt-Compilers im Sample (aktuell wird nur die Kompilierbarkeit des generierten
  Codes geprüft, nicht der vollständige Dagger-Graph)
- Veröffentlichung (`maven-publish`), Android-Sample, CI
