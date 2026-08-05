package de.mafo.hilt.provider.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toAnnotationSpec
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Generates Hilt `@Module`s for top-level functions annotated with `@Provide`.
 *
 * One module is generated per source file and target component, named after the file:
 * `NavEntry.kt` becomes `NavEntry_SingletonComponentModule`. Deriving the name from the file rather
 * than from the function keeps it unique — a package cannot contain two files with the same name,
 * while it can very well contain several functions called `provideNavEntry`.
 */
internal class HiltProviderProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(PROVIDE_ANNOTATION).toList()

        symbols
            .filterNot { it is KSFunctionDeclaration }
            .forEach { logger.error("@Provide is only applicable to functions.", it) }

        val deferred = mutableListOf<KSAnnotated>()

        symbols
            .filterIsInstance<KSFunctionDeclaration>()
            .groupBy { it.containingFile }
            .forEach { (file, functions) ->
                // A file is generated as a whole, so it is also deferred as a whole: emitting a
                // partial module now and the rest in a later round would clash on the file name.
                if (file == null || functions.any { !it.validate() }) {
                    deferred += functions
                    return@forEach
                }
                functions
                    .filter { it.isSupported() }
                    .groupBy { it.installInComponent() }
                    .forEach { (component, group) -> generateModule(resolver, file, component, group) }
            }

        return deferred
    }

    /** Reports every reason the function cannot be handled and returns whether it can. */
    private fun KSFunctionDeclaration.isSupported(): Boolean {
        var supported = true
        fun reject(reason: String) {
            logger.error(reason, this)
            supported = false
        }

        if (parentDeclaration != null) reject("@Provide functions must be top-level declarations.")
        if (Modifier.SUSPEND in modifiers) reject("@Provide does not support suspend functions.")
        if (typeParameters.isNotEmpty()) reject("@Provide does not support generic functions.")
        if (packageName.asString().isEmpty()) {
            reject("@Provide functions must not live in the root package.")
        }
        if (returnType?.resolve() == null) {
            reject("Unable to resolve the return type of '${simpleName.asString()}'.")
        }
        return supported
    }

    private fun generateModule(
        resolver: Resolver,
        file: KSFile,
        component: ClassName,
        functions: List<KSFunctionDeclaration>,
    ) {
        val packageName = file.packageName.asString()
        val moduleName = moduleName(file, component)

        val existing = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString("$packageName.$moduleName"),
        )
        if (existing != null) {
            logger.error(
                "Cannot generate '$moduleName': the package already declares a type with that " +
                    "name. Rename it or move the @Provide functions to a differently named file.",
                functions.first(),
            )
            return
        }

        val module = TypeSpec.objectBuilder(moduleName)
            .addModifiers(KModifier.INTERNAL)
            .addAnnotation(MODULE)
            .addAnnotation(
                AnnotationSpec.builder(INSTALL_IN)
                    .addMember("%L", CodeBlock.of("%T::class", component))
                    .build(),
            )
            .apply { providesFunctions(packageName, functions).forEach(::addFunction) }
            .build()

        FileSpec.builder(packageName, moduleName)
            .addType(module)
            .build()
            .writeTo(codeGenerator, Dependencies(aggregating = false, file))
    }

    /**
     * Dagger rejects several binding methods with the same name in one module, so overloads are
     * disambiguated by their parameter types. That keeps the names stable when another overload is
     * added later, which an index-based suffix would not.
     */
    private fun providesFunctions(
        packageName: String,
        functions: List<KSFunctionDeclaration>,
    ): List<FunSpec> {
        val overloaded = functions
            .groupBy { it.simpleName.asString() }
            .filterValues { it.size > 1 }
            .keys
        val taken = mutableSetOf<String>()

        return functions.map { function ->
            val originalName = function.simpleName.asString()
            val candidate = if (originalName in overloaded) {
                originalName + function.parameters.joinToString("") { it.type.resolve().simpleName() }
            } else {
                originalName
            }
            val providesName = candidate.uniqueIn(taken)

            val parameters = function.parameters.map { parameter ->
                ParameterSpec
                    .builder(
                        name = parameter.name?.asString() ?: "arg",
                        type = parameter.type.resolve().toTypeName(),
                    )
                    .addAnnotations(parameter.annotations.map { it.toAnnotationSpec() }.toList())
                    .build()
            }

            FunSpec.builder(providesName)
                .apply {
                    if (providesName != originalName) {
                        addKdoc(
                            "Renamed from `%L`: Dagger does not allow overloaded binding methods.",
                            originalName,
                        )
                    }
                }
                .addAnnotation(PROVIDES)
                // Everything except our own marker is forwarded, so scopes (@Singleton), qualifiers
                // and multibinding annotations keep working as usual.
                .addAnnotations(
                    function.annotations
                        .filterNot { it.isProvideAnnotation() }
                        .map { it.toAnnotationSpec() }
                        .toList(),
                )
                .addParameters(parameters)
                .returns(function.returnType!!.resolve().toTypeName())
                // The call has to be fully qualified: the generated function may share its name
                // with the annotated one, in which case an unqualified call would recurse.
                .addStatement(
                    "return %L.%L(%L)",
                    packageName,
                    originalName,
                    parameters.joinToString { it.name },
                )
                .build()
        }
    }

    private fun moduleName(file: KSFile, component: ClassName): String {
        val fileName = file.fileName.removeSuffix(".kt").toIdentifier()
        return "${fileName}_${component.simpleName}$MODULE_SUFFIX"
    }

    /** The component from `@Provide(into = ...)`, falling back to [SINGLETON_COMPONENT]. */
    private fun KSFunctionDeclaration.installInComponent(): ClassName = annotations
        .firstOrNull { it.isProvideAnnotation() }
        ?.arguments
        ?.firstOrNull { it.name?.asString() == INTO_ARGUMENT }
        ?.value
        ?.let { it as? KSType }
        ?.declaration
        ?.qualifiedName
        ?.asString()
        ?.let(ClassName::bestGuess)
        ?: SINGLETON_COMPONENT

    private fun KSAnnotation.isProvideAnnotation(): Boolean =
        annotationType.resolve().declaration.qualifiedName?.asString() == PROVIDE_ANNOTATION

    private fun KSType.simpleName(): String = declaration.simpleName.asString()

    private fun String.toIdentifier(): String =
        map { if (it.isLetterOrDigit() || it == '_') it else '_' }
            .joinToString(separator = "")
            .let { if (it.firstOrNull()?.isDigit() == true) "_$it" else it }

    private fun String.uniqueIn(taken: MutableSet<String>): String {
        var candidate = this
        var index = 2
        while (!taken.add(candidate)) {
            candidate = "$this$index"
            index++
        }
        return candidate
    }

    private companion object {
        const val PROVIDE_ANNOTATION = "de.mafo.hilt.provider.Provide"
        const val INTO_ARGUMENT = "into"
        const val MODULE_SUFFIX = "Module"

        val MODULE = ClassName("dagger", "Module")
        val PROVIDES = ClassName("dagger", "Provides")
        val INSTALL_IN = ClassName("dagger.hilt", "InstallIn")
        val SINGLETON_COMPONENT = ClassName("dagger.hilt.components", "SingletonComponent")
    }
}
