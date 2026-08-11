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

## Verwendung

Voraussetzung ist ein funktionierendes Hilt-Setup im Konsumentenmodul — dieses Plugin ergänzt Hilt,
es ersetzt nichts davon:

```kotlin
plugins {
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

dependencies {
    implementation("de.mafo.hilt:hilt-provider-annotations:0.1.0-SNAPSHOT")
    ksp("de.mafo.hilt:hilt-provider-processor:0.1.0-SNAPSHOT")

    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")
}
```

Das Annotations-Modul exponiert `hilt-core` als `api`, damit `SingletonComponent` und `@InstallIn`
zum Kompilieren zur Verfügung stehen. Hilts **Compiler** ist damit nicht abgedeckt: ohne
`hilt-android-compiler` im jeweiligen Modul werden die generierten `@Module`s von niemandem
eingesammelt. In einem Mehr-Modul-Projekt gehört er in jedes Modul, das `@Provide` verwendet — nicht
nur in das mit der Hilt-Root.

Das Hilt-Gradle-Plugin dagegen braucht nur das Modul, in dem die Hilt-Root (`@HiltAndroidApp`) oder
ein `@AndroidEntryPoint` liegt. Eine reine Library mit `@Provide`-Deklarationen kommt ohne aus — so
gebaut in `sample-android:feature`.

Danach genügt eine Top-Level-Deklaration, ein eigenes `@Module` ist nicht mehr nötig. Lokal
installieren lässt sich das Plugin mit `./gradlew publishToMavenLocal`. Im Konsumentenprojekt muss
`mavenLocal()` dann in `dependencyResolutionManagement` stehen — in den Standard-Repositories ist
`~/.m2/repository` nicht enthalten.

## Multibindings

Multibindings laufen über den Parameter `multibinding`, nicht über Daggers eigene Annotationen:

```kotlin
@Provide(multibinding = IntoSet)
fun provideHomeEntry(): NavEntry = NavEntry("home")

@Provide(multibinding = ElementsIntoSet)
fun provideLegacyEntries(): Set<NavEntry> = setOf(NavEntry("legacy"), NavEntry("about"))

@Provide(multibinding = IntoMap)
@StringKey("login")
fun provideLoginHandler(): Handler = LoginHandler()
```

Der Import ist `de.mafo.hilt.provider.Multibinding.IntoSet` — die Enum-Einträge heißen absichtlich
wie die Dagger-Annotationen, in die der Processor sie übersetzt.

**Warum nicht direkt `@IntoSet`?** Weil das Weiterreichen zwar funktioniert, die Annotation aber
zusätzlich auf der Ursprungsdeklaration stehen bleibt — und Dagger beansprucht
`dagger.multibindings.*` überall, wo sie auftaucht. Der zuständige Processing-Step will melden, dass
hier ein `@Provides` fehlt, sucht dafür vorher den umschließenden Typ, und eine Top-Level-Funktion
hat keinen. Selbst der Fehlerpfad bricht deshalb mit
`java.lang.IllegalStateException: No enclosing TypeElement` ab, isoliert nachgewiesen ohne
`@Provide`, ohne Modul und ohne Component. Auf der *generierten* Funktion ist dieselbe Annotation
unproblematisch, weil sie dort in einem `object` liegt. `@Provide` lehnt die Dagger-Annotationen
darum weiterhin ab und verweist auf den Parameter.

Map-Keys (`@StringKey`, `@ClassKey`, eigene mit `@MapKey`) bleiben normale weitergereichte
Annotationen. Fehlt bei `IntoMap` der Map-Key, meldet das der Processor — Dagger täte es sonst erst
beim Zusammenbau des Components und dann gegen das generierte Modul.

Auf der Injektionsseite braucht Kotlin `@JvmSuppressWildcards`, sonst passt der Typ nicht auf das,
was Dagger gebunden hat:

```kotlin
@Inject lateinit var navEntries: Set<@JvmSuppressWildcards NavEntry>
@Inject lateinit var handlers: Map<String, @JvmSuppressWildcards Handler>
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
| `sample`       | JVM-Beispielmodul, das den Processor via `ksp(project(":processor"))` anwendet |
| `sample-android:feature` | Android-Library mit den `@Provide`-Deklarationen |
| `sample-android:app`     | Android-App mit der Hilt-Root (`@HiltAndroidApp`) |

## Build

```bash
./gradlew build                            # alles bauen + Tests
./gradlew :processor:test
./gradlew :sample:kspKotlin                # Generierung im JVM-Sample ausführen
./gradlew :sample-android:app:assembleDebug   # Android-Sample inkl. Hilt-Root bauen
```

Das Android-Sample braucht ein SDK; `local.properties` mit `sdk.dir=…` ist nicht eingecheckt.

Generierter Code des Samples: `sample/build/generated/ksp/main/kotlin/...`

## Aktueller Stand

Die Pipeline ist end-to-end verifiziert: Annotation → KSP-Processor → generiertes Hilt-Modul →
kompiliert im Sample.

Bereits umgesetzt:

- Top-Level-Funktionen und Top-Level-`val`s (inkl. `by lazy`)
- Generierung eines `internal object`-Moduls pro Quelldatei und Component (siehe Namensschema)
- Disambiguierung von Overloads über die Parametertypen
- `@InstallIn`-Component über den Annotationsparameter `into` (Default `SingletonComponent`). Der
  Typ muss `@DefineComponent` tragen — das gilt für Hilts eingebaute Components ebenso wie für
  eigene, eine hartkodierte Liste braucht es dafür nicht
- Multibindings über den Parameter `multibinding` (`IntoSet`, `ElementsIntoSet`, `IntoMap`); die
  Dagger-Annotation entsteht erst an der generierten Funktion, wo sie unproblematisch ist
- Weitergabe aller übrigen Annotationen (Scopes, Qualifier, Map-Keys) an die `@Provides`-Methode.
  Gegen `dagger-compiler` verifiziert: ein `@Named`-Entry-Point löst das qualifizierte Binding auf,
  für Funktionen wie für `val`s. KotlinPoet schreibt den Argumentnamen dabei aus und escapt ihn
  (``@Named(`value` = "base-url")``) — gültiges Kotlin und bytecode-identisch, bleibt so
- Generierte Module sind `internal`: sie sollen die API-Fläche der Konsumentenmodule nicht
  vergrößern, da Hilt sie ohnehin über `@InstallIn` einsammelt statt über direkte Referenzen
- Voll qualifizierter Delegate-Aufruf (verhindert Shadowing durch die generierte Funktion)
- Fehlermeldungen für alles, was nicht aufrufbar wäre: Member-Deklarationen, `private`,
  Extension-Funktionen und -Properties, `var`, `suspend`, Generics, Root-Package
- Fehlermeldung, wenn das Package den Modulnamen schon belegt
- Inferierte Rückgabetypen (`fun provideX() = createX()`)
- Parametrisierte und nullable Rückgabetypen (`List<Item>`, `Map<String, Item>`, `Item?`,
  Funktionstypen). Funktionstypen erscheinen als `Function1<String, Item>` — derselbe JVM-Typ und
  damit dasselbe Binding

Noch offen:

- Parametername `into` vs. `installIn` (Überschneidung mit `@IntoSet`/`@IntoMap`)
- Veröffentlichung bleibt bewusst bei `mavenLocal`, solange die API Platzhalter ist. Für ein
  Remote-Ziel käme hinzu: bei einer Package Registry ein Token, bei Maven Central zusätzlich
  Namespace-Verifikation, GPG-Signierung und `url`/`scm`/`developers` im POM
- CI ist zurückgestellt, bis das Projekt ein Remote hat

### Mehr-Modul-Fall

`sample-android` belegt den realistischen Aufbau: die `@Provide`-Deklarationen liegen in einer
Android-Library, die Hilt-Root (`@HiltAndroidApp`) in der App. Die Aggregation trägt über
Modulgrenzen, und `internal` ist dabei kein Problem — Hilt hat dafür einen eigenen Mechanismus und
generiert einen öffentlichen Wrapper:

```java
@InstallIn(SingletonComponent.class)
@Module(includes = Greeting_SingletonComponentModule.class)
@Generated("dagger.hilt.processor.internal.aggregateddeps.PkgPrivateModuleGenerator")
public final class HiltWrapper_Greeting_SingletonComponentModule {}
```

Im generierten Component landet daraus eine vollständige Verdrahtung, inklusive Singleton-Caching
über `DoubleCheck` und dem qualifizierten `val` aus der Library. Auf einem Gerät (Pixel 9 Pro,
Android 17) zeigt die App entsprechend `Hello from a library module (sample)` — beide Werte stammen
aus dem Library-Modul, der Text aus der `@Provide`-Funktion, `(sample)` aus dem `@Provide val`.

Dasselbe Sample deckt die Multibindings ab: `HomeEntry.kt`, `DetailEntry.kt` und `LegacyEntries.kt`
binden in *dasselbe* `Set<NavEntry>`, liegen aber in drei Dateien und damit in drei generierten
Modulen — Hilt führt sie zusammen. `Handlers.kt` steuert die `@IntoMap`-Seite bei. Verifiziert ist
das über `assembleDebug`: dagger-compiler validiert den Graphen dabei vollständig, ein erneuter
Gerätelauf steht noch aus.

### Bewusst nicht unterstützt

Zwei Grenzen kommen von Dagger, nicht von uns — beide mit `dagger-compiler` nachgeprüft:

- **`suspend`**: Dagger erkennt ein suspendierendes `@Provides` überhaupt nicht (im Bytecode hat es
  einen `Continuation`-Parameter) und meldet stattdessen `[Dagger/MissingBinding] … cannot be
  provided` am Component. Wir lehnen früher ab und zeigen dabei auf die Funktion selbst.
- **Typparametrisierte Funktionen** (`fun <T> provideList(): List<T>`): Dagger lehnt mit
  *"@Provides methods may not have type parameters"* ab. Ein parametrisierter *Rückgabetyp* ist
  davon nicht betroffen und funktioniert.
- **Daggers Multibinding-Annotationen direkt an der Deklaration** (`@IntoSet`, `@IntoMap`,
  `@ElementsIntoSet`): dagger-compiler bricht mit
  `java.lang.IllegalStateException: No enclosing TypeElement` ab, sobald eine davon auf einer
  Top-Level-Funktion steht — isoliert nachgewiesen, ohne `@Provide`, ohne Modul und ohne Component.
  Der Processor lehnt sie ab und verweist auf `@Provide(multibinding = …)`, siehe
  [Multibindings](#multibindings). Multibindings selbst sind also unterstützt, nur nicht auf diesem
  Weg.

Der Dagger-Graph wird bewusst **nicht** getestet — das wäre ein Test von Dagger. Geprüft wird, dass
der generierte Code exakt der erwarteten Form entspricht und beim Aufruf an die annotierte Funktion
delegiert. Einmalig gegen `dagger-compiler` verifiziert wurde lediglich die Namensfrage bei
Overloads: ohne Umbenennung bricht Dagger ab, mit Umbenennung entstehen kollisionsfreie Factories.

## Lizenz und Wartung

[Apache-2.0](LICENSE), Copyright 2026 Martin Förster.

Das Projekt ist für ein konkretes Vorhaben entstanden und wird nach Bedarf gepflegt, nicht als
Community-Projekt. Nutzung ist ausdrücklich erwünscht; Support, Roadmap oder eine zeitnahe
Bearbeitung von Issues und Pull Requests sind damit aber nicht zugesagt.
