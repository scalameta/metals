package scala.meta.internal.protopc

import scala.jdk.CollectionConverters._

import scala.meta.internal.proto.tree.Proto
import scala.meta.internal.proto.tree.Proto._
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompilerConfig

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList

/**
 * Provides code completions for protobuf files.
 */
class ProtoCompletionProvider(
    compiler: ProtoMetalsCompiler,
    params: OffsetParams,
    config: PresentationCompilerConfig
) {

  // Scalar types in proto3
  private val scalarTypes = List(
    "double", "float", "int32", "int64", "uint32", "uint64", "sint32", "sint64",
    "fixed32", "fixed64", "sfixed32", "sfixed64", "bool", "string", "bytes"
  )

  // Top-level keywords
  private val topLevelKeywords = List(
    "syntax", "package", "import", "option", "message", "enum", "service",
    "extend"
  )

  // Field modifiers
  private val fieldModifiers = List(
    "repeated",
    "optional"
  )

  // Message-level keywords
  private val messageKeywords = List(
    "message", "enum", "oneof", "map", "reserved", "extensions", "option"
  )

  def completions(): CompletionList = {
    val items = compiler.parse(params) match {
      case Some(file) =>
        val context = determineContext(file, params.offset())
        completionsForContext(file, context)
      case None =>
        // Parse failed, provide basic completions
        topLevelKeywords.map(createKeywordCompletion)
    }

    val list = new CompletionList()
    list.setItems(items.asJava)
    list.setIsIncomplete(false)
    list
  }

  private sealed trait CompletionContext
  private case object TopLevel extends CompletionContext
  private case object InMessage extends CompletionContext
  private case object InEnum extends CompletionContext
  private case object InService extends CompletionContext
  private case object TypePosition extends CompletionContext
  private case object FieldModifierPosition extends CompletionContext

  private def determineContext(
      file: ProtoFile,
      offset: Int
  ): CompletionContext = {
    // Find enclosing declaration
    compiler.enclosingDeclaration(file, offset) match {
      case Some(_: MessageDecl) =>
        // Check if we're at the start of a field (type position)
        val textBefore = params.text().substring(0, offset)
        val lastNewline = textBefore.lastIndexOf('\n')
        val lineStart = if (lastNewline < 0) 0 else lastNewline + 1
        val lineText = textBefore.substring(lineStart).trim

        if (
          lineText.isEmpty || lineText.startsWith("//") || lineText
            .endsWith(";") || lineText.endsWith("{")
        ) {
          FieldModifierPosition
        } else if (
          lineText.startsWith("repeated ") || lineText
            .startsWith("optional ") || lineText.startsWith("required ")
        ) {
          TypePosition
        } else if (
          lineText.matches("^[a-zA-Z_][a-zA-Z0-9_]*$") || lineText.matches(
            "^(repeated|optional|required)\\s+[a-zA-Z_][a-zA-Z0-9_]*$"
          )
        ) {
          // Typing a type name
          TypePosition
        } else {
          InMessage
        }
      case Some(_: EnumDecl) => InEnum
      case Some(_: ServiceDecl) => InService
      case None => TopLevel
      case _ => TopLevel
    }
  }

  private def completionsForContext(
      file: ProtoFile,
      context: CompletionContext
  ): List[CompletionItem] = {
    context match {
      case TopLevel =>
        topLevelKeywords.map(createKeywordCompletion)

      case FieldModifierPosition =>
        fieldModifiers.map(createKeywordCompletion) ++
          scalarTypes.map(createTypeCompletion) ++
          collectMessageTypes(file).map(createMessageTypeCompletion) ++
          collectEnumTypes(file).map(createEnumTypeCompletion) ++
          messageKeywords.map(createKeywordCompletion)

      case TypePosition =>
        scalarTypes.map(createTypeCompletion) ++
          collectMessageTypes(file).map(createMessageTypeCompletion) ++
          collectEnumTypes(file).map(createEnumTypeCompletion)

      case InMessage =>
        fieldModifiers.map(createKeywordCompletion) ++
          scalarTypes.map(createTypeCompletion) ++
          collectMessageTypes(file).map(createMessageTypeCompletion) ++
          collectEnumTypes(file).map(createEnumTypeCompletion) ++
          messageKeywords.map(createKeywordCompletion)

      case InEnum =>
        List(createKeywordCompletion("option"))

      case InService =>
        List(createKeywordCompletion("rpc"), createKeywordCompletion("option"))
    }
  }

  private def collectMessageTypes(file: ProtoFile): List[String] = {
    def collect(decls: java.util.List[Proto]): List[String] = {
      decls.asScala.toList.flatMap {
        case m: MessageDecl =>
          m.name().value() :: collectNested(m)
        case _ => Nil
      }
    }

    def collectNested(msg: MessageDecl): List[String] = {
      msg.nestedMessages().asScala.toList.flatMap { nested =>
        nested.name().value() :: collectNested(nested)
      }
    }

    collect(file.declarations())
  }

  private def collectEnumTypes(file: ProtoFile): List[String] = {
    def collect(decls: java.util.List[Proto]): List[String] = {
      decls.asScala.toList.flatMap {
        case e: EnumDecl =>
          List(e.name().value())
        case m: MessageDecl =>
          m.nestedEnums().asScala.toList.map(_.name().value())
        case _ => Nil
      }
    }

    collect(file.declarations())
  }

  private def createKeywordCompletion(keyword: String): CompletionItem = {
    val item = new CompletionItem(keyword)
    item.setKind(CompletionItemKind.Keyword)
    item.setSortText("0" + keyword) // Keywords first
    item
  }

  private def createTypeCompletion(typeName: String): CompletionItem = {
    val item = new CompletionItem(typeName)
    item.setKind(CompletionItemKind.TypeParameter)
    item.setDetail("scalar type")
    item.setSortText("1" + typeName)
    item
  }

  private def createMessageTypeCompletion(typeName: String): CompletionItem = {
    val item = new CompletionItem(typeName)
    item.setKind(CompletionItemKind.Class)
    item.setDetail("message")
    item.setSortText("2" + typeName)
    item
  }

  private def createEnumTypeCompletion(typeName: String): CompletionItem = {
    val item = new CompletionItem(typeName)
    item.setKind(CompletionItemKind.Enum)
    item.setDetail("enum")
    item.setSortText("2" + typeName)
    item
  }
}
