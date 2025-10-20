package scala.meta.internal.jpc

case class JavaKeyword(
    name: String,
    level: JavaKeyword.Level
)

object JavaKeyword {
  sealed trait Level

  case object TopLevel extends Level
  case object ClassLevel extends Level
  case object MethodLevel extends Level

  val topLevelKeywords: List[JavaKeyword] = List(
    "package", "import", "public", "private", "protected", "abstract", "class",
    "interface"
  ).map(JavaKeyword(_, TopLevel))

  val classLevelKeywords: List[JavaKeyword] = List(
    "public", "private", "protected", "static", "final", "native",
    "synchronized", "abstract", "default", "class", "interface", "void",
    "boolean", "int", "long", "float", "double", "extends", "implements",
    "char", "byte", "short"
  ).map(JavaKeyword(_, ClassLevel))

  val methodLevelKeywords: List[JavaKeyword] = List(
    "new", "assert", "try", "catch", "finally", "throw", "return", "break",
    "case", "continue", "default", "do", "while", "for", "switch", "if", "else",
    "instanceof", "var", "final", "class", "void", "boolean", "int", "long",
    "float", "double", "char", "byte", "short"
  ).map(JavaKeyword(_, MethodLevel))

  val all: List[JavaKeyword] =
    topLevelKeywords ++ classLevelKeywords ++ methodLevelKeywords
}
