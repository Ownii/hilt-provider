# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Was das Projekt ist

KSP-Plugin, das Top-Level-Provider für Hilt ermöglicht: eine mit `@Provide` annotierte
Top-Level-Funktion wird zur Build-Zeit in ein generiertes `@Module`/`@Provides`-Paar verpackt, weil
Dagger `@Provides` nur innerhalb eines `@Module` erlaubt.

API und Namensschema (`@Provide(into = ...)`, ein Modul pro Datei und Component) sind entschieden;
offene Punkte stehen unter "Aktueller Stand" in `README.md`.

## Commands

```bash
./gradlew build                       # alle Module + Tests
./gradlew :processor:test             # nur Processor-Tests
./gradlew :processor:test --tests '*HiltProviderProcessorTest.generates*'   # einzelner Test
./gradlew :sample:kspKotlin           # Generierung im JVM-Sample ausführen
./gradlew :sample-android:app:assembleDebug   # Android-Sample (braucht local.properties mit sdk.dir)
./gradlew publishToMavenLocal         # nur :annotations und :processor
```

Generierten Code des Samples ansehen (schnellster Weg, eine Processor-Änderung zu prüfen):
`sample/build/generated/ksp/main/kotlin/de/mafo/hilt/provider/sample/`

## Architektur

Drei Module, Abhängigkeitsrichtung `sample → processor → annotations`:

- **`annotations`** — die öffentliche API-Fläche (`de.mafo.hilt.provider.Provide` plus das Enum
  `Multibinding`). Exponiert
  `hilt-core` als `api`-Abhängigkeit, weil `SingletonComponent` als Default des
  `into`-Parameters Teil der Annotationssignatur ist.
- **`processor`** — `HiltProviderProcessor` (KSP + KotlinPoet) plus `HiltProviderProcessorProvider`.
  Der Provider muss in
  `processor/src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
  registriert bleiben, sonst läuft der Processor stillschweigend nicht.
- **`sample`** — reines Verifikationsmodul (JVM): wendet `ksp(project(":processor"))` an und beweist,
  dass der generierte Code kompiliert. Kein `dagger-compiler` — Dagger-Verhalten wird bei Bedarf mit
  Wegwerf-Proben geprüft, nicht dauerhaft im Build.
- **`sample-android:feature` / `sample-android:app`** — der Mehr-Modul-Fall: `@Provide` in einer
  Android-Library, Hilt-Root in der App. Deckt ab, was das JVM-Sample nicht kann, nämlich Hilts
  Aggregation über Modulgrenzen — und, seit den Multibindings, über mehrere generierte Module
  hinweg: `HomeEntry.kt`, `DetailEntry.kt` und `LegacyEntries.kt` binden in dasselbe
  `Set<NavEntry>`. Die Injektionsstellen brauchen `@JvmSuppressWildcards`, sonst sucht Dagger nach
  `Set<? extends NavEntry>`. Achtung: AGP 9 bringt Kotlin-Support eingebaut mit, das
  `kotlin-android`-Plugin darf hier **nicht** angewandt werden. `MainActivity` setzt Textfarbe,
  Hintergrund und `Gravity.CENTER` bewusst explizit: das Sample hat kein Theme (im Dark Mode wäre
  der Text sonst weiß auf hell) und `targetSdk 36` bedeutet Edge-to-Edge, wodurch Inhalt am oberen
  Rand hinter der Action Bar verschwindet. Der injizierte Wert wird zusätzlich per `Log.i` mit Tag
  `hilt-provider` ausgegeben, damit die Laufzeitprüfung ohne Blick auf den Bildschirm geht.

### Invarianten des Processors

Diese Punkte sind bewusst so und beim Umbau leicht kaputtzumachen:

- **Ein Modul pro (Quelldatei, Component)**, benannt nach der Datei:
  `NavEntry.kt` → `NavEntry_SingletonComponentModule`. Der Name kommt von der Datei, weil ein
  Package keine zwei Dateien gleichen Namens haben kann, aber sehr wohl mehrere Funktionen
  `provideNavEntry`. Der Component-Suffix ist nötig, weil `@InstallIn` am Modul hängt.
- **Overloads werden über die Parametertypen umbenannt** (`provideLabel(Boolean)` →
  `provideLabelBoolean`). Grund ist eine harte Dagger-Grenze, empirisch verifiziert: *"Cannot have
  more than one binding method with the same name in a single module"*. Parametertypen statt Index,
  damit ein später hinzugefügter Overload die bestehenden Namen nicht verschiebt.
- **Delegation ist voll qualifiziert** (`de.mafo.hilt.provider.sample.provideConfig(...)`). Das
  generierte Modul liegt im selben Package und die `@Provides`-Funktion trägt in der Regel denselben
  Namen wie die Ursprungsfunktion — ein unqualifizierter Aufruf würde auf sie selbst auflösen
  (Endlosrekursion). Deshalb wird für das Root-Package ein Fehler gemeldet.
- **Eine Datei wird als Ganzes generiert oder als Ganzes deferred.** Würde ein Teil der Funktionen
  jetzt und der Rest in einer späteren KSP-Runde geschrieben, kollidierte der Dateiname.
- **Alle Annotationen außer `@Provide` werden weitergegeben**, damit Scopes, Qualifier und Map-Keys
  unverändert funktionieren. **Ausnahme Multibindings**: `@IntoSet`, `@IntoMap` und
  `@ElementsIntoSet` an der Deklaration werden abgelehnt. Nicht weil das Weiterreichen scheitert,
  sondern weil die Annotation auch auf der Ursprungsdeklaration stehen bleibt und dagger-compiler
  dort mit `IllegalStateException: No enclosing TypeElement` abbricht — isoliert nachgewiesen. Die
  Erkennung prüft erst den `shortName` und resolved nur Kandidaten.
- **Multibindings laufen über `@Provide(multibinding = …)`** mit dem Enum `Multibinding`
  (`None`/`IntoSet`/`ElementsIntoSet`/`IntoMap`). Die Enum-Einträge heißen wie die
  Dagger-Annotationen, damit die Übersetzung ein `ClassName(MULTIBINDING_PACKAGE, name)` bleibt und
  keine Mapping-Tabelle braucht, die auseinanderlaufen kann. Die Annotation entsteht erst an der
  generierten Funktion, die in einem `object` liegt — genau deshalb funktioniert dort, was an der
  Top-Level-Deklaration abstürzt. Achtung beim Lesen des Arguments: KSP liefert den Enum-Eintrag als
  `KSClassDeclaration`, nicht als `KSType` wie bei `into` und nicht als Kotlin-Enum; empirisch
  ermittelt (`KSClassDeclarationEnumEntryImpl`).
- **`IntoMap` ohne Map-Key wird abgelehnt.** Die Prüfung sitzt in `toProvideTargetOrNull()` und ist
  auf `IntoMap` gegated, kostet also für alle anderen Deklarationen nichts. Erkannt wird ein Map-Key
  an Daggers Meta-Annotation `@MapKey`, damit eigene Keys mitzählen.
- **`into` wird gegen `@DefineComponent` geprüft.** Hilts eingebaute Components tragen die
  Annotation selbst (im Bytecode von `SingletonComponent` nachgesehen), deshalb deckt eine Regel
  eingebaute und eigene Components ab, ohne pflegebedürftige Liste. KSP liest sie auch aus
  kompilierten Jars, obwohl sie nur CLASS-Retention hat — mit `:sample:kspKotlin --rerun-tasks`
  geprüft. Die Regel sitzt in `toProvideTargetOrNull()` statt in `isSupported()`, weil sie die
  aufgelöste Marker-Annotation braucht, die dort ohnehin vorliegt.
- Unterstützt werden Top-Level-**Funktionen und Properties** (`val`, auch `by lazy`); die
  Annotation trägt dafür `@Target(FUNCTION, PROPERTY)`.
- Abgelehnt wird mit `logger.error`, was der generierte Aufruf nicht erreichen könnte:
  Member-Deklarationen, `private` (generiertes Modul liegt in einer anderen Datei),
  Extension-Funktionen/-Properties (kein Receiver), `var`, sowie `suspend` und Generics.
- **`internal` ist für Hilt unproblematisch**: Hilt erkennt nicht-öffentliche Module und generiert
  über `PkgPrivateModuleGenerator` einen öffentlichen `HiltWrapper_…`, der unser Modul via
  `includes` einbindet. Im Android-Sample verifiziert.
- Ungültige Symbole werden über `validate()` an die nächste KSP-Runde zurückgegeben (`process`
  liefert die deferred-Liste). Das ist kein Luxus: schaltet man die Deferral-Bedingung ab, endet ein
  `@Provide` auf einen noch nicht generierten Typ in `INTERNAL_ERROR`. Der Test
  `defers a whole file …` deckt das ab und ist genau so gegengeprüft — mit ausgeschaltetem Deferral
  schlägt er fehl.

### Tests

Testphilosophie: der Dagger-Graph wird bewusst nicht getestet (das wäre ein Test von Dagger).
Geprüft wird stattdessen (a) die exakte Form der generierten Datei per Vollvergleich und (b) das
Verhalten — ein Test lädt das generierte Modul über `result.classLoader` und ruft die
`@Provides`-Funktion per Reflection auf. Nur dieser Aufruf beweist, dass die Delegation nicht auf
sich selbst zeigt.

Ablehnungsregeln liegen als `@ParameterizedTest` mit `@MethodSource("rejections")` vor — eine neue
Regel ist dort ein Listeneintrag, kein neuer Testblock. `ModuleObject.call(name)` sucht die Methode
allein über den Namen und prüft damit implizit mit, dass im generierten Modul keine zwei
Bindungsmethoden gleichen Namens landen, also genau das, was Dagger verbietet.

`processor/src/test/.../HiltProviderProcessorTest.kt` nutzt kotlin-compile-testing (kctfork) im
**KSP2-Modus** (`useKsp2()`); generierte Dateien werden über `compilation.kspSourcesDir` gelesen.
Die dafür nötigen Opt-ins (`ExperimentalCompilerApi`, `KspExperimental`) stehen in
`processor/build.gradle.kts` — ohne sie schlägt schon die Test-Kompilierung fehl.

## Veröffentlichung

Nur `annotations` und `processor` wenden `maven-publish` an; die Konfiguration (Artefaktname
`hilt-provider-<modulname>`, Sources-/Javadoc-Jar, POM) liegt im Root unter
`plugins.withId("maven-publish")`. Die Samples dürfen nie publiziert werden — deshalb Opt-in pro
Modul statt `allprojects`.

Ausgeliefert wird über **JitPack**, das einen Git-Tag auf Abruf baut. Zwei Dinge daran sind
load-bearing:

- **`jitpack.yml` ist Pflicht, nicht Kosmetik.** JitPack führt bei `maven-publish` von sich aus
  `./gradlew build publishToMavenLocal` aus, was `sample-android` mitbaut — dort gibt es kein
  Android-SDK. Der `install`-Block beschränkt den Lauf auf die beiden Library-Module; dass das ohne
  SDK durchläuft, ist mit beiseitegelegter `local.properties` und ohne `ANDROID_HOME` geprüft (AGP
  braucht sein SDK erst, wenn eine seiner Tasks im Graphen steht). Der Standard-JDK dort ist
  OpenJDK 8, mit dem Gradle 9 nicht startet — daher `jdk: openjdk17`.
- **`group` und `version` kommen aus der Umgebung** (`GROUP`, `VERSION`), die JitPack setzt, mit
  `de.mafo.hilt` und `0.1.0-SNAPSHOT` als Default für lokale Builds. Die Koordinate, unter der
  JitPack tatsächlich ausliefert, setzt es selbst und weicht ab:
  `com.github.Ownii.hilt-provider:hilt-provider-annotations:<Tag>` — bei Mehr-Modul-Projekten hängt
  der Repository-Name an der Group. Aus dem Build-Log abgelesen, nicht angenommen.
- Der JitPack-Lauf führt **keine Tests** aus. Ein Tag ist damit kein Qualitätsnachweis; `./gradlew
  build` gehört vor das Taggen.

## Build-Konventionen

- Versionen ausschließlich über `gradle/libs.versions.toml` (Version Catalog, `libs.*`-Aliase).
- JVM-Target ist bewusst **17**, obwohl lokal JDK 25 baut — die Artefakte müssen für
  Android-/Hilt-Konsument*innen nutzbar bleiben. Durchgesetzt wird das **einmal im Root-Build**
  (`subprojects { plugins.withId("org.jetbrains.kotlin.jvm") { … } }`), nicht pro Modul: sonst
  kompiliert ein neues Modul stillschweigend gegen das JDK-Default und bricht erst bei den
  Konsument*innen. Kein `jvmToolchain`-Block, entsprechend auch kein Toolchain-Resolver in
  `settings.gradle.kts`. Die Android-Module setzen nur `compileOptions`; den Kotlin-jvmTarget
  leitet AGP daraus ab.
- KSP-Version ist von der Kotlin-Version entkoppelt (eigenes Schema, aktuell `2.3.11`); beide
  getrennt anheben und danach `./gradlew build` prüfen.
- kotlin-compile-testing bringt eine **eigene, ältere KSP-Implementierung** mit. Nur
  `symbol-processing-api` zieht Gradle automatisch hoch, `symbol-processing-aa-embeddable` und
  `-common-deps` blieben zurück — getestet würde dann gegen eine andere KSP-Runtime als die
  ausgelieferte. `processor/build.gradle.kts` erzwingt deshalb die Katalog-Version für die ganze
  Gruppe `com.google.devtools.ksp` in allen `test*`-Konfigurationen. Bricht das nach einem
  KSP-Bump, ist kctfork noch nicht soweit — dann ist die Meldung laut, nicht still.
