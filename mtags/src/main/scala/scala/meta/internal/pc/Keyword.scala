package scala.meta.internal.pc
// scalafmt: { maxColumn = 120 }

case class Keyword(
    name: String,
    // Can this keyword appear in expression position?
    isExpression: Boolean = false,
    // Can this keyword appear in a block statement position?
    isBlock: Boolean = false,
    // Can this keyword appear in a template statement position?
    isTemplate: Boolean = false,
    // Can this keyword appear in a package statement position?
    isPackage: Boolean = false,
    // Can this keyword appear in a def block statement position?
    isMethodBody: Boolean = false,
    // Does this keyword define a symbol? For example "def" or "class"
    isDefinition: Boolean = false,
    // Is located in param definition
    isParam: Boolean = false,
    // Is this keyword only in Scala 3?
    isScala3: Boolean = false,
    // Optional character to select this completion item, for example "."
    commitCharacter: Option[String] = None
) {

  def insertText: String =
    if (needsTrailingWhitespace) name + " "
    else name

  private def needsTrailingWhitespace: Boolean =
    name match {
      case "this" | "super" | "true" | "false" | "null" => false
      case _ => true
    }

  def matchesPosition(
      name: String,
      isExpression: Boolean,
      isBlock: Boolean,
      isDefinition: Boolean,
      isMethodBody: Boolean,
      isTemplate: Boolean,
      isPackage: Boolean,
      isParam: Boolean,
      isScala3: Boolean,
      allowToplevel: Boolean
  ): Boolean = {
    val isAllowedInThisScalaVersion = (this.isScala3 && isScala3) || !this.isScala3
    this.name.startsWith(name) && isAllowedInThisScalaVersion && {
      (this.isExpression && isExpression) ||
      (this.isBlock && isBlock) ||
      (this.isDefinition && isDefinition) ||
      (this.isTemplate && isTemplate) ||
      (this.isTemplate && allowToplevel && isPackage) ||
      (this.isPackage && isPackage) ||
      (this.isMethodBody && isMethodBody) ||
      (this.isParam && isParam)
    }
  }
}

object Keyword {

  val all: List[Keyword] = List(
    Keyword("def", isBlock = true, isTemplate = true, isDefinition = true),
    Keyword("val", isBlock = true, isTemplate = true, isDefinition = true),
    Keyword("lazy val", isBlock = true, isTemplate = true, isDefinition = true),
    Keyword("inline", isBlock = true, isTemplate = true, isDefinition = true, isPackage = true, isScala3 = true),
    Keyword("using", isParam = true, isScala3 = true),
    Keyword("var", isBlock = true, isTemplate = true, isDefinition = true),
    Keyword("given", isBlock = true, isTemplate = true, isDefinition = true, isScala3 = true),
    Keyword("extension", isBlock = true, isTemplate = true, isDefinition = true, isPackage = true, isScala3 = true),
    Keyword("type", isTemplate = true, isDefinition = true),
    Keyword("class", isTemplate = true, isPackage = true, isDefinition = true),
    Keyword("enum", isTemplate = true, isPackage = true, isDefinition = true, isScala3 = true),
    Keyword("case class", isTemplate = true, isPackage = true, isDefinition = true),
    Keyword("trait", isTemplate = true, isPackage = true, isDefinition = true),
    Keyword("object", isTemplate = true, isPackage = true, isDefinition = true),
    Keyword("package", isPackage = true),
    Keyword("import", isBlock = true, isTemplate = true, isPackage = true),
    Keyword("final", isTemplate = true, isPackage = true),
    Keyword("private", isTemplate = true, isPackage = true),
    Keyword("protected", isTemplate = true, isPackage = true),
    Keyword("abstract class", isTemplate = true, isPackage = true),
    Keyword("sealed trait", isTemplate = true, isPackage = true),
    Keyword("sealed abstract class", isTemplate = true, isPackage = true),
    Keyword("sealed class", isTemplate = true, isPackage = true),
    Keyword("super", isExpression = true, commitCharacter = Some(".")),
    Keyword("this", isExpression = true, commitCharacter = Some(".")),
    Keyword("if", isExpression = true),
    Keyword("new", isExpression = true),
    Keyword("for", isExpression = true),
    Keyword("while", isExpression = true),
    Keyword("do", isExpression = true),
    Keyword("true", isExpression = true),
    Keyword("false", isExpression = true),
    Keyword("null", isExpression = true),
    Keyword("try", isExpression = true),
    Keyword("throw", isExpression = true),
    Keyword("implicit", isBlock = true, isTemplate = true),
    Keyword("return", isMethodBody = true),
    Keyword("match"), // already implemented by CompletionPosition
    Keyword("case"), // already implemented by CompletionPosition and "case class"
    Keyword("override"), // already implemented by CompletionPosition
    Keyword("forSome"), // in-frequently used language feature
    Keyword("macro"), // in-frequently used language feature
    // The keywords below were left out in the first iteration of implementing keyword completions
    // since they appear in positions that are a bit more difficult to detect on the syntax tree.
    Keyword("extends"),
    Keyword("with"),
    Keyword("catch"),
    Keyword("extends"),
    Keyword("finally"),
    Keyword("then")
  )

}
