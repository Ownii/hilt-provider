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
    fun `generates a hilt module named after the source file`() {
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
        assertThat(compilation.generatedFile("test/Providers_SingletonComponentModule.kt")).isEqualTo(
            """
            package test

            import dagger.Module
            import dagger.Provides
            import dagger.hilt.InstallIn
            import dagger.hilt.components.SingletonComponent
            import javax.inject.Singleton

            @Module
            @InstallIn(SingletonComponent::class)
            internal object Providers_SingletonComponentModule {
              @Provides
              @Singleton
              public fun provideApiClient(config: Config): ApiClient = test.provideApiClient(config)
            }

            """.trimIndent(),
        )
    }

    @Test
    fun `collects all functions of a file into one module`() {
        val (compilation, result) = compile(
            SourceFile.kotlin(
                "Providers.kt",
                """
                package test

                import de.mafo.hilt.provider.Provide

                @Provide
                fun provideName(): String = "name"

                @Provide
                fun provideCount(): Int = 1
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val generated = compilation.generatedFile("test/Providers_SingletonComponentModule.kt")
        assertThat(generated).contains("public fun provideName(): String = test.provideName()")
        assertThat(generated).contains("public fun provideCount(): Int = test.provideCount()")
    }

    /**
     * A package cannot hold two files with the same name, so identically named provider functions
     * in different packages never collide – the common case for something like `provideNavEntry`.
     */
    @Test
    fun `generates independent modules for equally named functions in different packages`() {
        val (compilation, result) = compile(
            SourceFile.kotlin(
                "NavEntry.kt",
                """
                package test.home

                import de.mafo.hilt.provider.Provide

                @Provide
                fun provideNavEntry(): String = "home"
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "NavEntry2.kt",
                """
                package test.detail

                import de.mafo.hilt.provider.Provide

                @Provide
                fun provideNavEntry(): String = "detail"
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
        assertThat(compilation.generatedFile("test/home/NavEntry_SingletonComponentModule.kt"))
            .contains("test.home.provideNavEntry()")
        assertThat(compilation.generatedFile("test/detail/NavEntry2_SingletonComponentModule.kt"))
            .contains("test.detail.provideNavEntry()")
    }

    /**
     * Dagger rejects overloaded binding methods ("Cannot have more than one binding method with the
     * same name in a single module"), so the generated names have to differ – while both still
     * delegate to their respective overload.
     */
    @Test
    fun `disambiguates overloads by parameter type`() {
        val (compilation, result) = compile(
            SourceFile.kotlin(
                "Providers.kt",
                """
                package test

                import de.mafo.hilt.provider.Provide

                @Provide
                fun provideGreeting(): String = "hello"

                @Provide
                fun provideGreeting(count: Int): String = "hello".repeat(count)
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val generated = compilation.generatedFile("test/Providers_SingletonComponentModule.kt")
        assertThat(generated).contains("public fun provideGreetingInt(count: Int): String")

        val moduleClass = result.classLoader.loadClass("test.Providers_SingletonComponentModule")
        val module = moduleClass.getField("INSTANCE").get(null)

        assertThat(moduleClass.getDeclaredMethod("provideGreeting").invoke(module) as String)
            .isEqualTo("hello")
        assertThat(
            moduleClass.getDeclaredMethod("provideGreetingInt", Int::class.java)
                .invoke(module, 2) as String,
        ).isEqualTo("hellohello")
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

        val moduleClass = result.classLoader.loadClass("test.Providers_SingletonComponentModule")
        val module = moduleClass.getField("INSTANCE").get(null)
        val provided = moduleClass.getDeclaredMethod("provideGreeting").invoke(module)

        assertThat(provided as String).isEqualTo("hello")
    }

    @Test
    fun `generates one module per component of a file`() {
        val (compilation, result) = compile(
            SourceFile.kotlin(
                "Providers.kt",
                """
                package test

                import de.mafo.hilt.provider.Provide

                class CustomComponent

                @Provide
                fun provideName(): String = "name"

                @Provide(into = CustomComponent::class)
                fun provideCount(): Int = 1
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
        assertThat(compilation.generatedFile("test/Providers_SingletonComponentModule.kt"))
            .contains("@InstallIn(SingletonComponent::class)")
        assertThat(compilation.generatedFile("test/Providers_CustomComponentModule.kt"))
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
        assertThat(compilation.generatedFile("test/Providers_SingletonComponentModule.kt"))
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

        val generated = compilation.generatedFile("test/Providers_SingletonComponentModule.kt")
        assertThat(generated).contains("@Singleton")
        // KotlinPoet spells out the argument name and escapes `value`, since it is a soft keyword.
        assertThat(generated).contains("""@Named(`value` = "base-url")""")
        assertThat(generated).contains("@Provides")
        // Our own marker must not leak into the generated code.
        assertThat(generated).doesNotContain("@Provide\n")
    }

    @Test
    fun `provides top level properties`() {
        val (compilation, result) = compile(
            SourceFile.kotlin(
                "Providers.kt",
                """
                package test

                import de.mafo.hilt.provider.Provide
                import javax.inject.Named
                import javax.inject.Singleton

                class Config(val baseUrl: String)

                @Provide
                @Singleton
                val defaultConfig = Config("https://example.com")

                @Provide
                @Named("greeting")
                val lazyGreeting: String by lazy { "hello" }
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val generated = compilation.generatedFile("test/Providers_SingletonComponentModule.kt")
        assertThat(generated).contains("public fun defaultConfig(): Config = test.defaultConfig")
        assertThat(generated).contains("""@Named(`value` = "greeting")""")

        // A property read must reach the property, not the generated function of the same name.
        val moduleClass = result.classLoader.loadClass("test.Providers_SingletonComponentModule")
        val module = moduleClass.getField("INSTANCE").get(null)
        assertThat(moduleClass.getDeclaredMethod("lazyGreeting").invoke(module) as String)
            .isEqualTo("hello")
    }

    @Test
    fun `disambiguates a property clashing with a function of the same name`() {
        val (compilation, result) = compile(
            SourceFile.kotlin(
                "Providers.kt",
                """
                package test

                import de.mafo.hilt.provider.Provide

                class Label(val text: String)

                @Provide
                val label = Label("property")

                @Provide
                fun label(prefix: String): Label = Label(prefix)
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val generated = compilation.generatedFile("test/Providers_SingletonComponentModule.kt")
        assertThat(generated).contains("public fun label(): Label = test.label")
        assertThat(generated).contains("public fun labelString(prefix: String): Label = test.label(prefix)")
    }

    @Test
    fun `reports an error for var properties`() {
        val (_, result) = compile(
            SourceFile.kotlin(
                "Providers.kt",
                """
                package test

                import de.mafo.hilt.provider.Provide

                @Provide
                var mutableValue: String = "value"
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        assertThat(result.messages).contains("does not support 'var' properties")
    }

    @Test
    fun `reports an error for private declarations`() {
        val (_, result) = compile(
            SourceFile.kotlin(
                "Providers.kt",
                """
                package test

                import de.mafo.hilt.provider.Provide

                @Provide
                private fun provideValue(): String = "value"
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        assertThat(result.messages).contains("must not be private")
    }

    @Test
    fun `reports an error for extension functions`() {
        val (_, result) = compile(
            SourceFile.kotlin(
                "Providers.kt",
                """
                package test

                import de.mafo.hilt.provider.Provide

                class Config

                @Provide
                fun Config.provideValue(): String = "value"
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        assertThat(result.messages).contains("does not support extension functions")
    }

    @Test
    fun `reports an error when the module name is already taken`() {
        val (_, result) = compile(
            SourceFile.kotlin(
                "Providers.kt",
                """
                package test

                import de.mafo.hilt.provider.Provide

                class Providers_SingletonComponentModule

                @Provide
                fun provideValue(): String = "value"
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        assertThat(result.messages).contains("already declares a type with that name")
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
        assertThat(result.messages).contains("must be applied to top-level declarations")
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
