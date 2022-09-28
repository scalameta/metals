package scala.meta.internal.metals

import org.eclipse.lsp4j._
import scala.collection.JavaConverters._

object SemanticTokenCapability {
  var TokenTypes: List[String] = List(
    SemanticTokenTypes.Type,
    SemanticTokenTypes.Class,
    SemanticTokenTypes.Enum, // 3
    SemanticTokenTypes.Interface, // 4
    SemanticTokenTypes.Struct, // 5
    SemanticTokenTypes.TypeParameter, // 6
    SemanticTokenTypes.Parameter, // 7
    SemanticTokenTypes.Variable, // 8
    SemanticTokenTypes.Property, // 9
    SemanticTokenTypes.EnumMember, // 10
    SemanticTokenTypes.Event, // 11
    SemanticTokenTypes.Function, // 12
    SemanticTokenTypes.Method, // 13
    SemanticTokenTypes.Macro, // 14
    SemanticTokenTypes.Keyword, // 15
    SemanticTokenTypes.Modifier,
    SemanticTokenTypes.Comment,
    SemanticTokenTypes.String,
    SemanticTokenTypes.Number,
    SemanticTokenTypes.Regexp,
    SemanticTokenTypes.Operator,
    SemanticTokenTypes.Decorator,
  )

  var TokenModifiers: List[String] = List(
    SemanticTokenModifiers.Declaration,
    SemanticTokenModifiers.Definition,
    SemanticTokenModifiers.Readonly,
    SemanticTokenModifiers.Static,
    SemanticTokenModifiers.Deprecated,
    SemanticTokenModifiers.Abstract,
    SemanticTokenModifiers.Async,
    SemanticTokenModifiers.Modification,
    SemanticTokenModifiers.Documentation,
    SemanticTokenModifiers.DefaultLibrary,
  )

  val defaultServerCapability: SemanticTokensWithRegistrationOptions =
    new org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions(
      new SemanticTokensLegend(
        this.TokenTypes.asJava,
        this.TokenModifiers.asJava,
      ), // legend used in this server.
      new SemanticTokensServerFull(
        false
      ), // Method 'full' is supported, but 'full/delta' is not.
      false, // Method 'range' is not supported.
      // Dynamic registration is not supported.
    )

}
