package de.mafo.hilt.provider.ksp

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.isPrivate
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
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toAnnotationSpec
import com.squareup.kotlinpoet.ksp.toClassNameOrNull
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import de.mafo.hilt.provider.Provide

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
        val (providable, unsupported) = resolver
            .getSymbolsWithAnnotation(PROVIDE_ANNOTATION)
            .partition { it is KSFunctionDeclaration || it is KSPropertyDeclaration }

        unsupported.forEach {
            logger.error("@Provide is only applicable to functions and properties.", it)
        }

        val deferred = mutableListOf<KSAnnotated>()

        providable
            .filterIsInstance<KSDeclaration>()
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
                    .mapNotNull { it.toProvideTargetOrNull() }
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
        if (isPrivate()) {
            reject("@Provide declarations must not be private: the generated module lives in a separate file and could not call them.")
        }
        if (extensionReceiver != null) {
            reject("@Provide does not support extension declarations: the generated module has no receiver to call them on.")
        }
        multibindingAnnotation?.let { annotation ->
            // Forwarding the annotation is not the problem – it stays on the annotated declaration
            // as well, and dagger-compiler dies on that with an internal
            // "No enclosing TypeElement" error. Verified for @IntoSet, @IntoMap and
            // @ElementsIntoSet; map keys such as @StringKey and scopes are unaffected.
            reject("@Provide cannot be combined with ${annotation.shortName.asString()}: Dagger rejects multibinding annotations on top-level declarations. Write the @Module by hand for this binding.")
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
                if (returnType == null) {
                    reject("Unable to determine the return type of '${simpleName.asString()}'.")
                }
            }

            is KSPropertyDeclaration -> {
                if (isMutable) {
                    reject("@Provide does not support 'var' properties. Use a 'val' or a function.")
                }
            }
        }
        return supported
    }

    /**
     * A `@Provide` declaration plus the two things that would otherwise be looked up twice: the
     * target component and the annotations to forward. Everything else is derived from
     * [declaration] where it is needed, which keeps the function/property distinction out of this
     * layer.
     */
    private class ProvideTarget(
        val declaration: KSDeclaration,
        val component: ClassName,
        val forwardedAnnotations: List<AnnotationSpec>,
    ) {
        val originalName: String get() = declaration.simpleName.asString()
    }

    /**
     * Returns `null` after reporting an error. Unlike the rules in [isSupported] this one needs the
     * resolved marker annotation, so it lives here instead of costing a second pass over the
     * annotations of every declaration.
     */
    private fun KSDeclaration.toProvideTargetOrNull(): ProvideTarget? {
        // A single pass over the annotations yields both halves: our marker carries the component,
        // everything else is forwarded so that scopes (@Singleton), qualifiers and map keys keep
        // working as usual.
        val (marker, forwarded) = annotations.partition { it.isProvideAnnotation() }
        val declaredComponent = marker.firstOrNull().declaredComponent()

        if (declaredComponent != null && !declaredComponent.isHiltComponent()) {
            logger.error(
                "'${declaredComponent.declaration.simpleName.asString()}' is not a Hilt component: " +
                    "'into' expects a type annotated with @DefineComponent, such as " +
                    "SingletonComponent or ViewModelComponent.",
                this,
            )
            return null
        }

        return ProvideTarget(
            declaration = this,
            component = declaredComponent?.toClassNameOrNull() ?: SINGLETON_COMPONENT,
            forwardedAnnotations = forwarded.map { it.toAnnotationSpec() },
        )
    }

    private fun generateModule(
        resolver: Resolver,
        file: KSFile,
        component: ClassName,
        targets: List<ProvideTarget>,
    ) {
        val packageName = file.packageName.asString()
        val moduleName = moduleName(file, component)

        if (resolver.getClassDeclarationByName("$packageName.$moduleName") != null) {
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
                AnnotationSpec.builder(INSTALL_IN).addMember("%T::class", component).build(),
            )
            .addFunctions(providesFunctions(targets))
            .build()

        FileSpec.builder(packageName, moduleName)
            .addType(module)
            .build()
            .writeTo(codeGenerator, Dependencies(aggregating = false, file))
    }

    /**
     * Dagger rejects several binding methods with the same name in one module, so declarations that
     * share a name are disambiguated by their parameter types. That keeps the names stable when
     * another overload is added later, which an index-based suffix would not. Parameter types alone
     * are not unique either — two overloads can take equally named types from different packages —
     * so [claimIn] appends a counter as a last resort.
     */
    private fun providesFunctions(targets: List<ProvideTarget>): List<FunSpec> {
        val ambiguous = targets
            .groupBy { it.originalName }
            .filterValues { it.size > 1 }
            .keys
        val taken = mutableSetOf<String>()

        return targets.map { target ->
            val originalName = target.originalName
            // Only computed where it is needed: the suffix costs a resolve() per parameter.
            val candidate = if (originalName in ambiguous) {
                originalName + target.parameterTypeSuffix()
            } else {
                originalName
            }
            val providesName = candidate.claimIn(taken)

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
                .addAnnotations(target.forwardedAnnotations)
                .delegateTo(target.declaration)
                .build()
        }
    }

    /**
     * Adds signature and body. The call is fully qualified because the generated function usually
     * shares its name with the annotated one, in which case an unqualified call would recurse.
     */
    private fun FunSpec.Builder.delegateTo(declaration: KSDeclaration): FunSpec.Builder {
        val packageName = declaration.packageName.asString()
        val name = declaration.simpleName.asString()

        return when (declaration) {
            is KSFunctionDeclaration -> {
                val parameters = declaration.parameters.map { parameter ->
                    ParameterSpec
                        .builder(parameter.name?.asString() ?: "arg", parameter.type.toTypeName())
                        .addAnnotations(parameter.annotations.map { it.toAnnotationSpec() }.toList())
                        .build()
                }
                addParameters(parameters)
                    .returns(declaration.returnType!!.toTypeName())
                    .addStatement(
                        "return %L.%L(%L)",
                        packageName,
                        name,
                        parameters.joinToString { it.name },
                    )
            }

            is KSPropertyDeclaration ->
                returns(declaration.type.toTypeName())
                    .addStatement("return %L.%L", packageName, name)

            else -> error("Unsupported declaration: $name")
        }
    }

    private fun ProvideTarget.parameterTypeSuffix(): String = (declaration as? KSFunctionDeclaration)
        ?.parameters
        ?.joinToString(separator = "") { it.type.resolve().declaration.simpleName.asString() }
        .orEmpty()

    private fun moduleName(file: KSFile, component: ClassName): String {
        val fileName = file.fileName.substringBeforeLast('.').toIdentifier()
        return "${fileName}_${component.simpleName}$MODULE_SUFFIX"
    }

    /** The type from `@Provide(into = ...)`, or `null` when the default applies. */
    private fun KSAnnotation?.declaredComponent(): KSType? = this
        ?.arguments
        ?.firstOrNull { it.name?.asString() == INTO_ARGUMENT }
        ?.value as? KSType

    /**
     * Hilt's built-in components carry `@DefineComponent` themselves — verified in the bytecode of
     * `SingletonComponent` — so one rule covers built-in and custom components alike, without a
     * hardcoded list that would age.
     */
    private fun KSType.isHiltComponent(): Boolean = declaration.annotations.any {
        it.shortName.asString() == DEFINE_COMPONENT_NAME &&
            it.annotationType.resolve().declaration.qualifiedName?.asString() == DEFINE_COMPONENT
    }

    private fun KSAnnotation.isProvideAnnotation(): Boolean =
        annotationType.resolve().declaration.qualifiedName?.asString() == PROVIDE_ANNOTATION

    /**
     * The short name is checked first, which is free: only a candidate is resolved, so unrelated
     * annotations cost nothing here.
     */
    private val KSDeclaration.multibindingAnnotation: KSAnnotation?
        get() = annotations.firstOrNull {
            it.shortName.asString() in MULTIBINDING_ANNOTATIONS &&
                it.annotationType.resolve().declaration.qualifiedName?.asString()
                    ?.startsWith(MULTIBINDING_PACKAGE) == true
        }

    private val KSDeclaration.extensionReceiver: KSTypeReference?
        get() = when (this) {
            is KSFunctionDeclaration -> extensionReceiver
            is KSPropertyDeclaration -> extensionReceiver
            else -> null
        }

    private fun String.toIdentifier(): String =
        map { if (it.isLetterOrDigit() || it == '_') it else '_' }
            .joinToString(separator = "")
            .let { if (it.firstOrNull()?.isDigit() == true) "_$it" else it }

    /** Returns this name, or the first free `name2`, `name3`, …, and records it as taken. */
    private fun String.claimIn(taken: MutableSet<String>): String {
        var candidate = this
        var index = 2
        while (!taken.add(candidate)) {
            candidate = "$this$index"
            index++
        }
        return candidate
    }

    private companion object {
        /** Read off the annotation, so renaming it is a compile error rather than a silent no-op. */
        val PROVIDE_ANNOTATION: String = requireNotNull(Provide::class.java.canonicalName)
        val INTO_ARGUMENT: String = Provide::into.name

        const val MODULE_SUFFIX = "Module"

        const val DEFINE_COMPONENT = "dagger.hilt.DefineComponent"
        const val DEFINE_COMPONENT_NAME = "DefineComponent"

        const val MULTIBINDING_PACKAGE = "dagger.multibindings."
        val MULTIBINDING_ANNOTATIONS = setOf("IntoSet", "IntoMap", "ElementsIntoSet")

        val MODULE = ClassName("dagger", "Module")
        val PROVIDES = ClassName("dagger", "Provides")
        val INSTALL_IN = ClassName("dagger.hilt", "InstallIn")
        val SINGLETON_COMPONENT = ClassName("dagger.hilt.components", "SingletonComponent")
    }
}
