package de.mafo.hilt.provider.ksp

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class HiltProviderProcessorTest {

    @Test
    fun `generates a hilt module named after the source file`() {
        val compiled = compileSuccessfully(
            providers(
                """
                import javax.inject.Singleton

                class Config
                class ApiClient(val config: Config)

                @Provide
                @Singleton
                fun provideApiClient(config: Config): ApiClient = ApiClient(config)
                """,
            ),
        )

        assertThat(compiled.module).isEqualTo(
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
    fun `collects all declarations of a file into one module`() {
        val compiled = compileSuccessfully(
            providers(
                """
                @Provide
                fun provideName(): String = "name"

                @Provide
                fun provideCount(): Int = 1
                """,
            ),
        )

        assertThat(compiled.module).contains("public fun provideName(): String = test.provideName()")
        assertThat(compiled.module).contains("public fun provideCount(): Int = test.provideCount()")
    }

    /**
     * A package cannot hold two files with the same name, so identically named provider functions
     * in different packages never collide – the common case for something like `provideNavEntry`.
     */
    @Test
    fun `generates independent modules for equally named functions in different packages`() {
        val compiled = compileSuccessfully(
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

        assertThat(compiled.generatedFile("test/home/NavEntry_SingletonComponentModule.kt"))
            .contains("test.home.provideNavEntry()")
        assertThat(compiled.generatedFile("test/detail/NavEntry2_SingletonComponentModule.kt"))
            .contains("test.detail.provideNavEntry()")
    }

    /**
     * Dagger rejects overloaded binding methods ("Cannot have more than one binding method with the
     * same name in a single module"), so the generated names have to differ – while both still
     * delegate to their respective overload.
     */
    @Test
    fun `disambiguates overloads by parameter type`() {
        val compiled = compileSuccessfully(
            providers(
                """
                @Provide
                fun provideGreeting(): String = "hello"

                @Provide
                fun provideGreeting(count: Int): String = "hello".repeat(count)
                """,
            ),
        )

        assertThat(compiled.module).contains("public fun provideGreetingInt(count: Int): String")

        val module = compiled.moduleObject()
        assertThat(module.call("provideGreeting")).isEqualTo("hello")
        assertThat(module.call("provideGreetingInt", 2)).isEqualTo("hellohello")
    }

    /** Parameter type names are not unique across packages, so a counter is the last resort. */
    @Test
    fun `falls back to a counter when parameter type names are not distinctive`() {
        val compiled = compileSuccessfully(
            SourceFile.kotlin(
                "Ids.kt",
                """
                package test.foo
                class Id
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "Ids2.kt",
                """
                package test.bar
                class Id
                """.trimIndent(),
            ),
            providers(
                """
                import test.bar.Id as BarId
                import test.foo.Id as FooId

                class Value

                @Provide
                fun provideValue(id: FooId): Value = Value()

                @Provide
                fun provideValue(id: BarId): Value = Value()
                """,
            ),
        )

        assertThat(compiled.module).contains("public fun provideValueId(")
        assertThat(compiled.module).contains("public fun provideValueId2(")
    }

    /**
     * The generated `@Provides` function shares its name with the annotated function, so an
     * unqualified call would resolve to itself. Only actually invoking the generated module proves
     * that the delegation reaches the original function instead of recursing.
     */
    @Test
    fun `generated module delegates to the annotated function`() {
        val compiled = compileSuccessfully(
            providers(
                """
                @Provide
                fun provideGreeting(): String = "hello"
                """,
            ),
        )

        assertThat(compiled.moduleObject().call("provideGreeting")).isEqualTo("hello")
    }

    @Test
    fun `generates one module per component of a file`() {
        val compiled = compileSuccessfully(
            providers(
                """
                import dagger.hilt.DefineComponent

                @DefineComponent
                interface CustomComponent

                @Provide
                fun provideName(): String = "name"

                @Provide(into = CustomComponent::class)
                fun provideCount(): Int = 1
                """,
            ),
        )

        assertThat(compiled.module).contains("@InstallIn(SingletonComponent::class)")
        assertThat(compiled.generatedFile("test/Providers_CustomComponentModule.kt"))
            .contains("@InstallIn(CustomComponent::class)")
    }

    @Test
    fun `resolves inferred return types`() {
        val compiled = compileSuccessfully(
            providers(
                """
                class MyDependency
                fun createMyDependency() = MyDependency()

                @Provide
                fun provideDependency() = createMyDependency()
                """,
            ),
        )

        assertThat(compiled.module)
            .contains("public fun provideDependency(): MyDependency = test.provideDependency()")
    }

    /**
     * Parameterised return types are ordinary Dagger bindings — only type *parameters* on the
     * binding method itself are rejected, by Dagger and therefore by us.
     */
    @Test
    fun `supports parameterised and nullable return types`() {
        val compiled = compileSuccessfully(
            providers(
                """
                class Item

                @Provide
                fun provideItems(): List<Item> = listOf(Item())

                @Provide
                fun provideHandlers(): Map<String, Item> = emptyMap()

                @Provide
                fun provideOptionalItem(): Item? = null

                @Provide
                fun provideItemFactory(): (String) -> Item = { Item() }
                """,
            ),
        )

        assertThat(compiled.module).contains("public fun provideItems(): List<Item> = test.provideItems()")
        assertThat(compiled.module)
            .contains("public fun provideHandlers(): Map<String, Item> = test.provideHandlers()")
        assertThat(compiled.module)
            .contains("public fun provideOptionalItem(): Item? = test.provideOptionalItem()")
        assertThat(compiled.module)
            .contains("public fun provideItemFactory(): (String) -> Item = test.provideItemFactory()")
    }

    @Test
    fun `provides top level properties`() {
        val compiled = compileSuccessfully(
            providers(
                """
                import javax.inject.Named
                import javax.inject.Singleton

                class Config(val baseUrl: String)

                @Provide
                @Singleton
                val defaultConfig = Config("https://example.com")

                @Provide
                @Named("greeting")
                val lazyGreeting: String by lazy { "hello" }
                """,
            ),
        )

        assertThat(compiled.module).contains("public fun defaultConfig(): Config = test.defaultConfig")
        assertThat(compiled.module).contains("""@Named(`value` = "greeting")""")
        // A property read must reach the property, not the generated function of the same name.
        assertThat(compiled.moduleObject().call("lazyGreeting")).isEqualTo("hello")
    }

    @Test
    fun `disambiguates a property clashing with a function of the same name`() {
        val compiled = compileSuccessfully(
            providers(
                """
                class Label(val text: String)

                @Provide
                val label = Label("property")

                @Provide
                fun label(prefix: String): Label = Label(prefix)
                """,
            ),
        )

        assertThat(compiled.module).contains("public fun label(): Label = test.label")
        assertThat(compiled.module)
            .contains("public fun labelString(prefix: String): Label = test.label(prefix)")
    }

    @Test
    fun `forwards qualifier annotations to the provides function`() {
        val compiled = compileSuccessfully(
            providers(
                """
                import javax.inject.Named
                import javax.inject.Singleton

                @Provide
                @Singleton
                @Named("base-url")
                fun provideBaseUrl(): String = "https://example.com"
                """,
            ),
        )

        assertThat(compiled.module).contains("@Singleton")
        // KotlinPoet spells out the argument name and escapes `value`, since it is a soft keyword.
        assertThat(compiled.module).contains("""@Named(`value` = "base-url")""")
        assertThat(compiled.module).contains("@Provides")
        // Our own marker must not leak into the generated code.
        assertThat(compiled.module).doesNotContain("@Provide\n")
    }

    /**
     * Map keys are forwarded like any other annotation – including a class literal, which has to
     * survive the round trip through KotlinPoet. Only the multibinding *entry* annotations
     * (`@IntoSet`, `@IntoMap`, `@ElementsIntoSet`) are rejected, see [rejections]: Dagger cannot
     * cope with those on a top-level declaration.
     */
    @Test
    fun `forwards map key annotations`() {
        val compiled = compileSuccessfully(
            providers(
                """
                import dagger.multibindings.ClassKey
                import dagger.multibindings.StringKey

                interface Handler
                class LoginHandler : Handler

                @Provide
                @StringKey("login")
                fun provideNamedHandler(): Handler = LoginHandler()

                @Provide
                @ClassKey(LoginHandler::class)
                fun provideKeyedHandler(): Handler = LoginHandler()
                """,
            ),
        )

        assertThat(compiled.module).contains("""@StringKey(`value` = "login")""")
        assertThat(compiled.module).contains("@ClassKey(`value` = LoginHandler::class)")
    }

    /**
     * A `@Provide` may reference a type another processor has yet to generate. In that round the
     * declaration does not `validate()`, so the whole file has to wait: generating the resolvable
     * half now and the rest in the next round would write the same file name twice. The pay-off is
     * observable here — both declarations end up in one module and nothing fails.
     */
    @Test
    fun `defers a whole file until another processor has generated the missing type`() {
        val compiled = compileSuccessfully(
            providers(
                """
                @Provide
                fun provideReady(): String = "ready"

                @Provide
                fun provideGenerated(): GeneratedDependency = GeneratedDependency()
                """,
            ),
            alsoRun = listOf(GeneratedDependencyProvider()),
        )

        assertThat(compiled.module).contains("public fun provideReady(): String = test.provideReady()")
        assertThat(compiled.module)
            .contains("public fun provideGenerated(): GeneratedDependency = test.provideGenerated()")
    }

    /** Emits `test.GeneratedDependency` in the first round, so round one cannot resolve it. */
    private class GeneratedDependencyProvider : SymbolProcessorProvider {
        override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
            object : SymbolProcessor {
                private var emitted = false

                override fun process(resolver: Resolver): List<KSAnnotated> {
                    if (!emitted) {
                        emitted = true
                        environment.codeGenerator
                            .createNewFile(Dependencies(aggregating = false), "test", "GeneratedDependency")
                            .use { it.write("package test\n\nclass GeneratedDependency\n".toByteArray()) }
                    }
                    return emptyList()
                }
            }
    }

    @Test
    fun `reports an error when the module name is already taken`() {
        val result = compile(
            providers(
                """
                class Providers_SingletonComponentModule

                @Provide
                fun provideValue(): String = "value"
                """,
            ),
        ).result

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        assertThat(result.messages).contains("already declares a type with that name")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejections")
    fun `reports an error for declarations the generated module could not reach`(
        @Suppress("UNUSED_PARAMETER") case: String,
        source: SourceFile,
        expectedMessage: String,
    ) {
        val result = compile(source).result

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        assertThat(result.messages).contains(expectedMessage)
    }

    private fun compile(
        vararg sources: SourceFile,
        alsoRun: List<SymbolProcessorProvider> = emptyList(),
    ): Compiled {
        val compilation = KotlinCompilation().apply {
            this.sources = sources.toList()
            inheritClassPath = true
            useKsp2()
            symbolProcessorProviders += HiltProviderProcessorProvider()
            symbolProcessorProviders += alsoRun
        }
        return Compiled(compilation, compilation.compile())
    }

    private fun compileSuccessfully(
        vararg sources: SourceFile,
        alsoRun: List<SymbolProcessorProvider> = emptyList(),
    ): Compiled = compile(*sources, alsoRun = alsoRun)
        .also { assertThat(it.result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK) }

    private class Compiled(
        private val compilation: KotlinCompilation,
        val result: JvmCompilationResult,
    ) {
        /** The module generated for a source built via [providers]. */
        val module: String get() = generatedFile("test/$DEFAULT_MODULE_NAME.kt")

        fun generatedFile(relativePath: String): String {
            val file = File(compilation.kspSourcesDir, "kotlin/$relativePath")
            check(file.exists()) {
                val found = compilation.kspSourcesDir.walkTopDown().filter(File::isFile).joinToString()
                "Expected generated file $relativePath, found: $found"
            }
            return file.readText()
        }

        fun moduleObject(fqName: String = "test.$DEFAULT_MODULE_NAME"): ModuleObject {
            val type = result.classLoader.loadClass(fqName)
            return ModuleObject(type, type.getField("INSTANCE").get(null))
        }
    }

    /** Reflective access to a generated `@Module object`. */
    private class ModuleObject(private val type: Class<*>, private val instance: Any) {
        /**
         * Looks the method up by name alone – which doubles as a check that the generated module
         * never holds two binding methods of the same name, exactly what Dagger forbids.
         */
        fun call(method: String, vararg arguments: Any): Any? =
            type.declaredMethods.single { it.name == method }.invoke(instance, *arguments)
    }

    private companion object {
        const val DEFAULT_MODULE_NAME = "Providers_SingletonComponentModule"

        /** A `Providers.kt` in package `test` with `@Provide` already imported. */
        fun providers(body: String): SourceFile = SourceFile.kotlin(
            "Providers.kt",
            """
            package test

            import de.mafo.hilt.provider.Provide

            ${body.trimIndent()}
            """.trimIndent(),
        )

        @JvmStatic
        fun rejections(): List<Arguments> = listOf(
            Arguments.of(
                "member function",
                providers(
                    """
                    class Holder {
                        @Provide
                        fun provideValue(): String = "value"
                    }
                    """,
                ),
                "must be applied to top-level declarations",
            ),
            Arguments.of(
                "root package",
                SourceFile.kotlin(
                    "Root.kt",
                    """
                    import de.mafo.hilt.provider.Provide

                    @Provide
                    fun provideValue(): String = "value"
                    """.trimIndent(),
                ),
                "must not live in the root package",
            ),
            Arguments.of(
                "private function",
                providers(
                    """
                    @Provide
                    private fun provideValue(): String = "value"
                    """,
                ),
                "must not be private",
            ),
            Arguments.of(
                "extension function",
                providers(
                    """
                    class Config

                    @Provide
                    fun Config.provideValue(): String = "value"
                    """,
                ),
                "does not support extension declarations",
            ),
            Arguments.of(
                "extension property",
                providers(
                    """
                    class Config

                    @Provide
                    val Config.value: String get() = "value"
                    """,
                ),
                "does not support extension declarations",
            ),
            Arguments.of(
                "var property",
                providers(
                    """
                    @Provide
                    var mutableValue: String = "value"
                    """,
                ),
                "does not support 'var' properties",
            ),
            Arguments.of(
                "suspend function",
                providers(
                    """
                    @Provide
                    suspend fun provideValue(): String = "value"
                    """,
                ),
                "does not support suspend functions",
            ),
            Arguments.of(
                "into is not a Hilt component",
                providers(
                    """
                    @Provide(into = String::class)
                    fun provideValue(): String = "value"
                    """,
                ),
                "is not a Hilt component",
            ),
            Arguments.of(
                "multibinding annotation",
                providers(
                    """
                    import dagger.multibindings.IntoSet

                    @Provide
                    @IntoSet
                    fun provideHandler(): String = "handler"
                    """,
                ),
                "Dagger rejects multibinding annotations on top-level declarations",
            ),
            Arguments.of(
                "generic function",
                providers(
                    """
                    @Provide
                    fun <T> provideList(): List<T> = emptyList()
                    """,
                ),
                "may not have type parameters",
            ),
        )
    }
}
