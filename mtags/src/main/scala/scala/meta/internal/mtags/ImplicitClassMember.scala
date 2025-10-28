package scala.meta.internal.mtags

/**
 * Represents an implicit class for indexing.
 * Only stores the class symbol - the presentation compiler will resolve
 * methods and perform type checking at completion time.
 *
 * @param classSymbol The symbol of the implicit class (e.g., "io/circe/syntax/EncoderOps#")
 */
case class ImplicitClassMember(
    classSymbol: String
)
