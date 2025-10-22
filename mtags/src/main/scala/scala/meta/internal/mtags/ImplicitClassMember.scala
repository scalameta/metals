package scala.meta.internal.mtags

import scala.meta.internal.semanticdb.Range

/**
 * Represents an implicit class and its extension methods.
 * These will be indexed to provide completions for implicit class methods.
 *
 * @param classSymbol The symbol of the implicit class (e.g., "io/circe/syntax/EncoderOps#")
 * @param paramType The symbol of the parameter type (e.g., "scala/Int#")
 * @param methodSymbol The symbol of the method provided by the implicit class
 * @param methodName The simple name of the method (e.g., "asJson")
 * @param range The range of the implicit class in the source file
 */
case class ImplicitClassMember(
    classSymbol: String,
    paramType: String,
    methodSymbol: String,
    methodName: String,
    range: Range
)

