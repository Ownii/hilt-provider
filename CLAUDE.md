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
./gradlew :sample:kspKotlin           # Generierung im Sample ausführen
```

Generierten Code des Samples ansehen (schnellster Weg, eine Processor-Änderung zu prüfen):
`sample/build/generated/ksp/main/kotlin/de/mafo/hilt/provider/sample/`

## Architektur

Drei Module, Abhängigkeitsrichtung `sample → processor → annotations`:

- **`annotations`** — die öffentliche API-Fläche (`de.mafo.hilt.provider.Provide`). Exponiert
  `hilt-core` als `api`-Abhängigkeit, weil `SingletonComponent` als Default des
  `into`-Parameters Teil der Annotationssignatur ist.
- **`processor`** — `HiltProviderProcessor` (KSP + KotlinPoet) plus `HiltProviderProcessorProvider`.
  Der Provider muss in
  `processor/src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
  registriert bleiben, sonst läuft der Processor stillschweigend nicht.
- **`sample`** — reines Verifikationsmodul: wendet `ksp(project(":processor"))` an und beweist, dass
  der generierte Code kompiliert.

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
- **Alle Annotationen außer `@Provide` werden weitergegeben**, damit Scopes, Qualifier und
  Multibinding-Annotationen unverändert funktionieren.
- Unterstützt werden Top-Level-**Funktionen und Properties** (`val`, auch `by lazy`); die
  Annotation trägt dafür `@Target(FUNCTION, PROPERTY)`.
- Abgelehnt wird mit `logger.error`, was der generierte Aufruf nicht erreichen könnte:
  Member-Deklarationen, `private` (generiertes Modul liegt in einer anderen Datei),
  Extension-Funktionen/-Properties (kein Receiver), `var`, sowie `suspend` und Generics.
- Ungültige Symbole werden über `validate()` an die nächste KSP-Runde zurückgegeben (`process`
  liefert die deferred-Liste).

### Tests

Testphilosophie: der Dagger-Graph wird bewusst nicht getestet (das wäre ein Test von Dagger).
Geprüft wird stattdessen (a) die exakte Form der generierten Datei per Vollvergleich und (b) das
Verhalten — ein Test lädt das generierte Modul über `result.classLoader` und ruft die
`@Provides`-Funktion per Reflection auf. Nur dieser Aufruf beweist, dass die Delegation nicht auf
sich selbst zeigt.

`processor/src/test/.../HiltProviderProcessorTest.kt` nutzt kotlin-compile-testing (kctfork) im
**KSP2-Modus** (`useKsp2()`); generierte Dateien werden über `compilation.kspSourcesDir` gelesen.
Die dafür nötigen Opt-ins (`ExperimentalCompilerApi`, `KspExperimental`) stehen in
`processor/build.gradle.kts` — ohne sie schlägt schon die Test-Kompilierung fehl.

## Build-Konventionen

- Versionen ausschließlich über `gradle/libs.versions.toml` (Version Catalog, `libs.*`-Aliase).
- JVM-Target ist bewusst **17**, obwohl lokal JDK 25 baut — die Artefakte müssen für
  Android-/Hilt-Konsument*innen nutzbar bleiben. Kein `jvmToolchain`-Block; stattdessen
  `compilerOptions.jvmTarget` plus `java.sourceCompatibility/targetCompatibility` pro Modul.
- KSP-Version ist von der Kotlin-Version entkoppelt (eigenes Schema, aktuell `2.3.11`); beide
  getrennt anheben und danach `./gradlew build` prüfen.
