package de.mafo.hilt.provider.ksp

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toAnnotationSpec
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Generates Hilt `@Module`s for top-level functions and properties annotated with `@Provide`.
 *
 * One module is generated per source file and target component, named after the file:
 * `NavEntry.kt` becomes `NavEntry_SingletonComponentModule`. Deriving the name from the file rather
 * than from the declaration keeps it unique — a package cannot contain two files with the same name,
 * while it can very well contain several functions called `provideNavEntry`.
 */
internal class HiltProviderProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(PROVIDE_ANNOTATION).toList()

        symbols
            .filterNot { it is KSFunctionDeclaration || it is KSPropertyDeclaration }
            .forEach { logger.error("@Provide is only applicable to functions and properties.", it) }

        val deferred = mutableListOf<KSAnnotated>()

        symbols
            .filterIsInstance<KSDeclaration>()
            .filter { it is KSFunctionDeclaration || it is KSPropertyDeclaration }
            .groupBy { it.containingFile }
            .forEach { (file, declarations) ->
                // A file is generated as a whole, so it is also deferred as a whole: emitting a
                // partial module now and the rest in a later round would clash on the file name.
                if (file == null || declarations.any { !it.validate() }) {
                    deferred += declarations
                    return@forEach
                }
                declarations
                    .filter { it.isSupported() }
                    .map { it.toProvideTarget() }
                    .groupBy { it.component }
                    .forEach { (component, targets) ->
                        generateModule(resolver, file, component, targets)
                    }
            }

        return deferred
    }

    /** Reports every reason the declaration cannot be handled and returns whether it can. */
    private fun KSDeclaration.isSupported(): Boolean {
        var supported = true
        fun reject(reason: String) {
            logger.error(reason, this)
            supported = false
        }

        if (parentDeclaration != null) reject("@Provide must be applied to top-level declarations.")
        if (packageName.asString().isEmpty()) {
            reject("@Provide declarations must not live in the root package.")
        }
        if (getVisibility() == Visibility.PRIVATE) {
            reject("@Provide declarations must not be private: the generated module lives in a separate file and could not call them.")
        }

        when (this) {
            is KSFunctionDeclaration -> {
                if (Modifier.SUSPEND in modifiers) {
                    // Dagger does not recognise a suspending @Provides at all – it compiles to a
                    // method taking a Continuation – and reports a MissingBinding at the component
                    // instead. Failing here points at the actual cause.
                    reject("@Provide does not support suspend functions: Dagger ignores suspending @Provides methods and then reports the binding as missing.")
                }
                if (typeParameters.isNotEmpty()) {
                    reject("@Provide does not support generic functions: Dagger rejects them with '@Provides methods may not have type parameters'. A parameterised return type such as List<Item> works.")
                }
                if (extensionReceiver != null) {
                    reject("@Provide does not support extension functions: the generated module has no receiver to call them on.")
                }
                if (returnType?.resolve() == null) {
                    reject("Unable to resolve the return type of '${simpleName.asString()}'.")
                }
            }

            is KSPropertyDeclaration -> {
                if (isMutable) {
                    reject("@Provide does not support 'var' properties. Use a 'val' or a function.")
                }
                if (extensionReceiver != null) {
                    reject("@Provide does not support extension properties: the generated module has no receiver to read them on.")
                }
            }
        }
        return supported
    }

    /** A `@Provide` function or property, reduced to what the generated `@Provides` needs. */
    private class ProvideTarget(
        val declaration: KSDeclaration,
        val originalName: String,
        val component: ClassName,
        val returnType: TypeName,
        val parameters: List<ParameterSpec>,
        val forwardedAnnotations: List<AnnotationSpec>,
        /** The fully qualified call or property read the generated function delegates to. */
        val delegate: CodeBlock,
        /** Appended to the generated name when several declarations share [originalName]. */
        val nameSuffix: String,
    )

    private fun KSDeclaration.toProvideTarget(): ProvideTarget {
        val packageName = packageName.asString()
        val name = simpleName.asString()
        val forwarded = annotations
            // Everything except our own marker is forwarded, so scopes (@Singleton), qualifiers and
            // multibinding annotations keep working as usual.
            .filterNot { it.isProvideAnnotation() }
            .map { it.toAnnotationSpec() }
            .toList()

        return when (this) {
            is KSFunctionDeclaration -> {
                val parameters = parameters.map { parameter ->
                    ParameterSpec
                        .builder(
                            name = parameter.name?.asString() ?: "arg",
                            type = parameter.type.resolve().toTypeName(),
                        )
                        .addAnnotations(parameter.annotations.map { it.toAnnotationSpec() }.toList())
                        .build()
                }
                ProvideTarget(
                    declaration = this,
                    originalName = name,
                    component = installInComponent(),
                    returnType = returnType!!.resolve().toTypeName(),
                    parameters = parameters,
                    forwardedAnnotations = forwarded,
                    // Fully qualified: the generated function may share its name with the annotated
                    // one, in which case an unqualified call would recurse.
                    delegate = CodeBlock.of(
                        "%L.%L(%L)",
                        packageName,
                        name,
                        parameters.joinToString { it.name },
                    ),
                    nameSuffix = this.parameters.joinToString("") {
                        it.type.resolve().declaration.simpleName.asString()
                    },
                )
            }

            is KSPropertyDeclaration -> ProvideTarget(
                declaration = this,
                originalName = name,
                component = installInComponent(),
                returnType = type.resolve().toTypeName(),
                parameters = emptyList(),
                forwardedAnnotations = forwarded,
                delegate = CodeBlock.of("%L.%L", packageName, name),
                nameSuffix = "",
            )

            else -> error("Unsupported declaration: $name")
        }
    }

    private fun generateModule(
        resolver: Resolver,
        file: KSFile,
        component: ClassName,
        targets: List<ProvideTarget>,
    ) {
        val packageName = file.packageName.asString()
        val moduleName = moduleName(file, component)

        val existing = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString("$packageName.$moduleName"),
        )
        if (existing != null) {
            logger.error(
                "Cannot generate '$moduleName': the package already declares a type with that " +
                    "name. Rename it or move the @Provide declarations to a differently named file.",
                targets.first().declaration,
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
            .apply { providesFunctions(targets).forEach(::addFunction) }
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
    private fun providesFunctions(targets: List<ProvideTarget>): List<FunSpec> {
        val ambiguous = targets
            .groupBy { it.originalName }
            .filterValues { it.size > 1 }
            .keys
        val taken = mutableSetOf<String>()

        return targets.map { target ->
            val candidate = if (target.originalName in ambiguous) {
                target.originalName + target.nameSuffix
            } else {
                target.originalName
            }
            val providesName = candidate.uniqueIn(taken)

            FunSpec.builder(providesName)
                .apply {
                    if (providesName != target.originalName) {
                        addKdoc(
                            "Renamed from `%L`: Dagger does not allow overloaded binding methods.",
                            target.originalName,
                        )
                    }
                }
                .addAnnotation(PROVIDES)
                .addAnnotations(target.forwardedAnnotations)
                .addParameters(target.parameters)
                .returns(target.returnType)
                .addStatement("return %L", target.delegate)
                .build()
        }
    }

    private fun moduleName(file: KSFile, component: ClassName): String {
        val fileName = file.fileName.removeSuffix(".kt").toIdentifier()
        return "${fileName}_${component.simpleName}$MODULE_SUFFIX"
    }

    /** The component from `@Provide(into = ...)`, falling back to [SINGLETON_COMPONENT]. */
    private fun KSDeclaration.installInComponent(): ClassName = annotations
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
