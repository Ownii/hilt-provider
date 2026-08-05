package de.mafo.hilt.provider.ksp

import assertk.assertThat
import assertk.assertions.contains
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
    fun `generates a hilt module delegating to the top level function`() {
        val (compilation, result) = compile(
            SourceFile.kotlin(
                "Providers.kt",
                """
                package test

                import de.mafo.hilt.provider.HiltProvider
                import javax.inject.Singleton

                class Config
                class ApiClient(val config: Config)

                @HiltProvider
                @Singleton
                fun provideApiClient(config: Config): ApiClient = ApiClient(config)
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val generated = compilation.generatedFile("test/ProvideApiClientHiltModule.kt")
        assertThat(generated).contains("@Module")
        assertThat(generated).contains("@InstallIn(SingletonComponent::class)")
        assertThat(generated).contains("internal object ProvideApiClientHiltModule")
        assertThat(generated).contains("@Provides")
        assertThat(generated).contains("@Singleton")
        // The delegation has to be fully qualified, otherwise the call would resolve to the
        // generated function itself.
        assertThat(generated).contains(
            "public fun provideApiClient(config: Config): ApiClient = test.provideApiClient(config)",
        )
    }

    @Test
    fun `reports an error for member functions`() {
        val (_, result) = compile(
            SourceFile.kotlin(
                "Holder.kt",
                """
                package test

                import de.mafo.hilt.provider.HiltProvider

                class Holder {
                    @HiltProvider
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
