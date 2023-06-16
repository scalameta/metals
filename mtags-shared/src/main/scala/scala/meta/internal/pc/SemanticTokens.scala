package scala.meta.internal.pc
import org.eclipse.lsp4j._

object SemanticTokens {
  val TokenTypes: List[String] = List(
    SemanticTokenTypes.Namespace,
    SemanticTokenTypes.Type,
    SemanticTokenTypes.Class,
    SemanticTokenTypes.Enum,
    SemanticTokenTypes.Interface,
    SemanticTokenTypes.Struct,
    SemanticTokenTypes.TypeParameter,
    SemanticTokenTypes.Parameter,
    SemanticTokenTypes.Variable,
    SemanticTokenTypes.Property,
    SemanticTokenTypes.EnumMember,
    SemanticTokenTypes.Event,
    SemanticTokenTypes.Function,
    SemanticTokenTypes.Method,
    SemanticTokenTypes.Macro,
    SemanticTokenTypes.Keyword,
    SemanticTokenTypes.Modifier,
    SemanticTokenTypes.Comment,
    SemanticTokenTypes.String,
    SemanticTokenTypes.Number,
    SemanticTokenTypes.Regexp,
    SemanticTokenTypes.Operator,
    SemanticTokenTypes.Decorator
  )

  val TokenModifiers: List[String] = List(
    SemanticTokenModifiers.Declaration,
    SemanticTokenModifiers.Definition,
    SemanticTokenModifiers.Readonly,
    SemanticTokenModifiers.Static,
    SemanticTokenModifiers.Deprecated,
    SemanticTokenModifiers.Abstract,
    SemanticTokenModifiers.Async,
    SemanticTokenModifiers.Modification,
    SemanticTokenModifiers.Documentation,
    SemanticTokenModifiers.DefaultLibrary
  )

  val getTypeId: Map[String, Int] = TokenTypes.zipWithIndex.toMap
  val getModifierId: Map[String, Int] = TokenModifiers.zipWithIndex.toMap

  // We need custom rules for picking correct node, if there are multiple symbols at the same position
  val getTypePriority: Map[Int, Int] = Map(
    // In .sbt on `List` are symbols `scala`, `collection`, `immutable`, `List`,
    // We want to pick `List`, so `namespace` has lower priority than `class`
    TokenTypes.indexOf(SemanticTokenTypes.Namespace) -> 4,
    TokenTypes.indexOf(SemanticTokenTypes.Type) -> 10,
    TokenTypes.indexOf(SemanticTokenTypes.Class) -> 8,
    // On parametrized enums like enum Foo(bar: Int): ...
    // We have both `class` and `enum`, so enum has higher priority than class
    TokenTypes.indexOf(SemanticTokenTypes.Enum) -> 9,
    TokenTypes.indexOf(SemanticTokenTypes.Interface) -> 7,
    TokenTypes.indexOf(SemanticTokenTypes.TypeParameter) -> 6,
    // In scala 2 for comprehensions including lines with `=`, we get `scala.x$1` symbols,
    // we don't want to choose `scala` package`, so `variable` has higher priority than `namespace`
    // See test `for-comprehension` in SemanticTokensSuite
    TokenTypes.indexOf(SemanticTokenTypes.Variable) -> 5,
    TokenTypes.indexOf(SemanticTokenTypes.EnumMember) -> 3,
    TokenTypes.indexOf(SemanticTokenTypes.Method) -> 2,
    // In `case class A(a: Int)` we have class variable `a` and `apply` and `copy` parameter `a`
    // We want to pick class variable, so parameter has lower priority
    TokenTypes.indexOf(SemanticTokenTypes.Parameter) -> 1
  ).withDefault(_ => 0)

}
