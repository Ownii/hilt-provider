package de.mafo.hilt.provider.ksp

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import java.io.File
import org.junit.jupiter.api.Test

class HiltProviderProcessorTest {

    @Test
    fun `generates a hilt module for a top level function`() {
        val (compilation, result) = compile(
            SourceFile.kotlin(
                "Providers.kt",
                """
                package test

                import de.mafo.hilt.provider.Provide
                import javax.inject.Singleton

                class Config
                class ApiClient(val config: Config)

                @Provide
                @Singleton
                fun provideApiClient(config: Config): ApiClient = ApiClient(config)
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
        assertThat(compilation.generatedFile("test/ProvideApiClientHiltModule.kt")).isEqualTo(
            """
            package test

            import dagger.Module
            import dagger.Provides
            import dagger.hilt.InstallIn
            import dagger.hilt.components.SingletonComponent
            import javax.inject.Singleton

            @Module
            @InstallIn(SingletonComponent::class)
            internal object ProvideApiClientHiltModule {
              @Provides
              @Singleton
              public fun provideApiClient(config: Config): ApiClient = test.provideApiClient(config)
            }

            """.trimIndent(),
        )
    }

    /**
     * The generated `@Provides` function shares its name with the annotated function, so an
     * unqualified call would resolve to itself. Only actually invoking the generated module proves
     * that the delegation reaches the original function instead of recursing.
     */
    @Test
    fun `generated module delegates to the annotated function`() {
        val (_, result) = compile(
            SourceFile.kotlin(
                "Providers.kt",
                """
                package test

                import de.mafo.hilt.provider.Provide

                @Provide
                fun provideGreeting(): String = "hello"
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val moduleClass = result.classLoader.loadClass("test.ProvideGreetingHiltModule")
        val module = moduleClass.getField("INSTANCE").get(null)
        val provided = moduleClass.getDeclaredMethod("provideGreeting").invoke(module)

        assertThat(provided as String).isEqualTo("hello")
    }

    @Test
    fun `installs the module into the component given by the into argument`() {
        val (compilation, result) = compile(
            SourceFile.kotlin(
                "Providers.kt",
                """
                package test

                import de.mafo.hilt.provider.Provide

                class CustomComponent

                @Provide(into = CustomComponent::class)
                fun provideValue(): String = "value"
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
        assertThat(compilation.generatedFile("test/ProvideValueHiltModule.kt"))
            .contains("@InstallIn(CustomComponent::class)")
    }

    @Test
    fun `resolves inferred return types`() {
        val (compilation, result) = compile(
            SourceFile.kotlin(
                "Providers.kt",
                """
                package test

                import de.mafo.hilt.provider.Provide

                class MyDependency
                fun createMyDependency() = MyDependency()

                @Provide
                fun provideDependency() = createMyDependency()
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
        assertThat(compilation.generatedFile("test/ProvideDependencyHiltModule.kt"))
            .contains("public fun provideDependency(): MyDependency = test.provideDependency()")
    }

    @Test
    fun `forwards qualifier annotations to the provides function`() {
        val (compilation, result) = compile(
            SourceFile.kotlin(
                "Providers.kt",
                """
                package test

                import de.mafo.hilt.provider.Provide
                import javax.inject.Named
                import javax.inject.Singleton

                @Provide
                @Singleton
                @Named("base-url")
                fun provideBaseUrl(): String = "https://example.com"
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val generated = compilation.generatedFile("test/ProvideBaseUrlHiltModule.kt")
        assertThat(generated).contains("@Singleton")
        // KotlinPoet spells out the argument name and escapes `value`, since it is a soft keyword.
        assertThat(generated).contains("""@Named(`value` = "base-url")""")
        assertThat(generated).contains("@Provides")
        // Our own marker must not leak into the generated code.
        assertThat(generated).doesNotContain("@Provide\n")
    }

    @Test
    fun `reports an error for member functions`() {
        val (_, result) = compile(
            SourceFile.kotlin(
                "Holder.kt",
                """
                package test

                import de.mafo.hilt.provider.Provide

                class Holder {
                    @Provide
                    fun provideValue(): String = "value"
                }
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        assertThat(result.messages).contains("must be top-level")
    }

    private fun compile(vararg sources: SourceFile): Pair<KotlinCompilation, JvmCompilationResult> {
        val compilation = KotlinCompilation().apply {
            this.sources = sources.toList()
            inheritClassPath = true
            useKsp2()
            symbolProcessorProviders += HiltProviderProcessorProvider()
        }
        return compilation to compilation.compile()
    }

    private fun KotlinCompilation.generatedFile(relativePath: String): String {
        val file = File(kspSourcesDir, "kotlin/$relativePath")
        check(file.exists()) {
            val found = kspSourcesDir.walkTopDown().filter(File::isFile).joinToString()
            "Expected generated file $relativePath, found: $found"
        }
        return file.readText()
    }
}
