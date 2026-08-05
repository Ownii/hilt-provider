package de.mafo.hilt.provider.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.validate
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
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
 * Generates a Hilt `@Module` for every top-level function annotated with `@HiltProvider`.
 *
 * The generated module simply delegates to the annotated function, which makes the function itself
 * a valid Dagger binding without the boilerplate object/module wrapper.
 */
internal class HiltProviderProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(HILT_PROVIDER_ANNOTATION).toList()
        val deferred = symbols.filterNot { it.validate() }

        symbols
            .filter { it.validate() }
            .forEach { symbol ->
                when (symbol) {
                    is KSFunctionDeclaration -> generate(symbol)
                    else -> logger.error("@HiltProvider is only applicable to functions.", symbol)
                }
            }

        return deferred
    }

    private fun generate(function: KSFunctionDeclaration) {
        if (!function.isTopLevel()) {
            logger.error("@HiltProvider functions must be top-level declarations.", function)
            return
        }
        if (Modifier.SUSPEND in function.modifiers) {
            logger.error("@HiltProvider does not support suspend functions.", function)
            return
        }
        if (function.typeParameters.isNotEmpty()) {
            logger.error("@HiltProvider does not support generic functions.", function)
            return
        }

        val containingFile = function.containingFile ?: return
        val packageName = function.packageName.asString()
        val functionName = function.simpleName.asString()
        val returnType = function.returnType?.resolve()?.toTypeName() ?: run {
            logger.error("Unable to resolve the return type of '$functionName'.", function)
            return
        }

        val component = function.hiltProviderComponent() ?: SINGLETON_COMPONENT
        val moduleName = "${functionName.replaceFirstChar(Char::uppercaseChar)}${MODULE_SUFFIX}"

        val parameters = function.parameters.map { parameter ->
            ParameterSpec
                .builder(
                    name = parameter.name?.asString() ?: "arg",
                    type = parameter.type.resolve().toTypeName(),
                )
                .addAnnotations(parameter.annotations.map { it.toAnnotationSpec() }.toList())
                .build()
        }

        // Everything except our own marker is forwarded, so scopes (@Singleton), qualifiers and
        // multibinding annotations keep working as usual.
        val forwardedAnnotations = function.annotations
            .filterNot { it.annotationType.resolve().declaration.qualifiedName?.asString() == HILT_PROVIDER_ANNOTATION }
            .map { it.toAnnotationSpec() }
            .toList()

        if (packageName.isEmpty()) {
            logger.error("@HiltProvider functions must not live in the root package.", function)
            return
        }
        // The generated function keeps the original name for readable Dagger error messages, which
        // means the call has to be fully qualified – otherwise it would resolve to itself.
        val delegate = "$packageName.$functionName"

        val provides = FunSpec.builder(functionName)
            .addAnnotation(PROVIDES)
            .addAnnotations(forwardedAnnotations)
            .addParameters(parameters)
            .returns(returnType)
            .addStatement("return %L(%L)", delegate, parameters.joinToString { it.name })
            .build()

        val module = TypeSpec.objectBuilder(moduleName)
            .addModifiers(KModifier.INTERNAL)
            .addAnnotation(MODULE)
            .addAnnotation(
                AnnotationSpec.builder(INSTALL_IN)
                    .addMember("%L", CodeBlock.of("%T::class", component))
                    .build(),
            )
            .addFunction(provides)
            .build()

        FileSpec.builder(packageName, moduleName)
            .addType(module)
            .build()
            .writeTo(
                codeGenerator = codeGenerator,
                dependencies = Dependencies(aggregating = false, containingFile),
            )
    }

    private fun KSFunctionDeclaration.isTopLevel(): Boolean = parentDeclaration == null

    private fun KSFunctionDeclaration.hiltProviderComponent(): ClassName? = annotations
        .firstOrNull { it.annotationType.resolve().declaration.qualifiedName?.asString() == HILT_PROVIDER_ANNOTATION }
        ?.arguments
        ?.firstOrNull { it.name?.asString() == "component" }
        ?.value
        ?.let { it as? KSType }
        ?.declaration
        ?.qualifiedName
        ?.asString()
        ?.let(ClassName::bestGuess)

    private companion object {
        const val HILT_PROVIDER_ANNOTATION = "de.mafo.hilt.provider.HiltProvider"
        const val MODULE_SUFFIX = "HiltModule"

        val MODULE = ClassName("dagger", "Module")
        val PROVIDES = ClassName("dagger", "Provides")
        val INSTALL_IN = ClassName("dagger.hilt", "InstallIn")
        val SINGLETON_COMPONENT = ClassName("dagger.hilt.components", "SingletonComponent")
    }
}
