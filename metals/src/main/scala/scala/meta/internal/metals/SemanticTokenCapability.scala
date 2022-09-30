package scala.meta.internal.metals

import scala.collection.JavaConverters._

import org.eclipse.lsp4j._

object SemanticTokenCapability {
  var TokenTypes: List[String] = List(
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
