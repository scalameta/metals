package scala.meta.internal.protopc

import java.util.Optional

import scala.jdk.CollectionConverters._

import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.internal.proto.tree.Proto
import scala.meta.internal.proto.tree.Proto._
import scala.meta.pc.ContentType
import scala.meta.pc.ContentType.MARKDOWN
import scala.meta.pc.HoverSignature
import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j

/**
 * Provides hover information for protobuf elements.
 */
class ProtoHoverProvider(
    compiler: ProtoMetalsCompiler,
    params: OffsetParams,
    contentType: ContentType
) {

  def hover(): Option[HoverSignature] = {
    for {
      file <- compiler.parse(params)
      node <- compiler.nodeAtOffset(file, params.offset())
      sig <- hoverSignature(file, node).orElse {
        // If we're on an Ident, check if it's the name of a declaration
        node match {
          case ident: Ident =>
            findDeclarationWithName(file, ident).flatMap(decl =>
              hoverSignature(file, decl)
            )
          case _ => None
        }
      }
    } yield ProtoHover(
      symbolSignature = Some(sig),
      contentType = contentType
    )
  }

  /** Find the declaration whose name is this identifier */
  private def findDeclarationWithName(
      file: ProtoFile,
      ident: Ident
  ): Option[Proto] = {
    import scala.jdk.CollectionConverters._

    def searchInMessage(msg: MessageDecl): Option[Proto] = {
      // Check fields
      msg
        .fields()
        .asScala
        .find(_.name() == ident)
        .map(f => f: Proto)
        .orElse {
          // Check nested messages
          msg
            .nestedMessages()
            .asScala
            .find(_.name() == ident)
            .map(m => m: Proto)
        }
        .orElse {
          // Search recursively in nested messages
          msg
            .nestedMessages()
            .asScala
            .iterator
            .map(searchInMessage)
            .collectFirst { case Some(p) => p }
        }
        .orElse {
          // Check nested enums
          msg.nestedEnums().asScala.find(_.name() == ident).map(e => e: Proto)
        }
        .orElse {
          // Check enum values within nested enums
          msg
            .nestedEnums()
            .asScala
            .flatMap(_.values().asScala)
            .find(_.name() == ident)
            .map(v => v: Proto)
        }
        .orElse {
          // Check oneofs
          msg.oneofs().asScala.find(_.name() == ident).map(o => o: Proto)
        }
        .orElse {
          // Check map fields
          msg.mapFields().asScala.find(_.name() == ident).map(m => m: Proto)
        }
    }

    def searchInService(svc: ServiceDecl): Option[Proto] = {
      svc.rpcs().asScala.find(_.name() == ident).map(r => r: Proto)
    }

    def searchInEnum(enum: EnumDecl): Option[Proto] = {
      enum.values().asScala.find(_.name() == ident).map(v => v: Proto)
    }

    // Check top-level declarations
    file
      .declarations()
      .asScala
      .iterator
      .map {
        case m: MessageDecl if m.name() == ident => Some(m: Proto)
        case e: EnumDecl if e.name() == ident => Some(e: Proto)
        case s: ServiceDecl if s.name() == ident => Some(s: Proto)
        case m: MessageDecl => searchInMessage(m)
        case e: EnumDecl => searchInEnum(e)
        case s: ServiceDecl => searchInService(s)
        case _ => None
      }
      .collectFirst { case Some(p) => p }
  }

  private def hoverSignature(file: ProtoFile, node: Proto): Option[String] = {
    node match {
      case msg: MessageDecl =>
        Some(s"message ${msg.name().value()}")
      case field: FieldDecl =>
        val modifier = field.modifier() match {
          case FieldModifier.REPEATED => "repeated "
          case FieldModifier.OPTIONAL => "optional "
          case FieldModifier.REQUIRED => "required "
          case _ => ""
        }
        Some(
          s"${modifier}${field.`type`().fullName()} ${field.name().value()} = ${field.number()}"
        )
      case enum: EnumDecl =>
        Some(s"enum ${enum.name().value()}")
      case value: EnumValueDecl =>
        Some(s"${value.name().value()} = ${value.number()}")
      case svc: ServiceDecl =>
        Some(s"service ${svc.name().value()}")
      case rpc: RpcDecl =>
        val clientStream = if (rpc.clientStreaming()) "stream " else ""
        val serverStream = if (rpc.serverStreaming()) "stream " else ""
        Some(
          s"rpc ${rpc.name().value()}(${clientStream}${rpc.inputType().fullName()}) returns (${serverStream}${rpc.outputType().fullName()})"
        )
      case oneof: OneofDecl =>
        Some(s"oneof ${oneof.name().value()}")
      case mapField: MapFieldDecl =>
        Some(
          s"map<${mapField.keyType().fullName()}, ${mapField.valueType().fullName()}> ${mapField.name().value()} = ${mapField.number()}"
        )
      case typeRef: TypeRef =>
        // Try to find what this type refers to
        findTypeDefinition(file, typeRef).map(hoverSignature(file, _)).flatten
      case _: Ident =>
        // Handled by findDeclarationWithName in hover()
        None
      case _ =>
        None
    }
  }

  private def findTypeDefinition(
      file: ProtoFile,
      typeRef: TypeRef
  ): Option[Proto] = {
    val typeName = typeRef.fullName()
    // Search for a message or enum with this name
    file.declarations().asScala.collectFirst {
      case m: MessageDecl if matchesTypeName(m, typeName) => m
      case e: EnumDecl if e.name().value() == typeName => e
    }
  }

  private def matchesTypeName(msg: MessageDecl, typeName: String): Boolean = {
    if (msg.name().value() == typeName) true
    else {
      // Check nested messages
      msg.nestedMessages().asScala.exists(matchesTypeName(_, typeName)) ||
      msg.nestedEnums().asScala.exists(_.name().value() == typeName)
    }
  }
}

/**
 * Hover result for protobuf.
 */
case class ProtoHover(
    expressionType: Option[String] = None,
    symbolSignature: Option[String] = None,
    docstring: Option[String] = None,
    forceExpressionType: Boolean = false,
    range: Option[lsp4j.Range] = None,
    override val contentType: ContentType
) extends HoverSignature {

  def signature(): Optional[String] = symbolSignature.asJava

  override def toLsp(): lsp4j.Hover = {
    val markup = protoHoverMarkup(
      expressionType.getOrElse(""),
      symbolSignature.getOrElse(""),
      docstring.getOrElse(""),
      forceExpressionType,
      markdown = contentType == MARKDOWN
    )
    new lsp4j.Hover(markup.toMarkupContent(contentType), range.orNull)
  }

  private def protoHoverMarkup(
      expressionType: String,
      symbolSignature: String,
      docstring: String,
      forceExpressionType: Boolean,
      markdown: Boolean
  ): String = {
    val builder = new StringBuilder()

    def addCode(title: Option[String], code: String): Unit = {
      title.foreach { title =>
        builder
          .append(if (markdown) "**" else "")
          .append(title)
          .append(if (markdown) "**" else "")
          .append(":\n")
      }
      builder
        .append(if (markdown) "```protobuf\n" else "")
        .append(code)
        .append(if (markdown) "\n```" else "")
    }

    if (forceExpressionType) {
      addCode(Some("Expression type"), expressionType)
      builder.append("\n")
    }

    if (symbolSignature.nonEmpty)
      addCode(
        if (forceExpressionType) Some("Symbol signature") else None,
        symbolSignature
      )

    if (docstring.nonEmpty)
      builder
        .append("\n")
        .append(docstring)
    builder.toString()
  }

  def getRange(): Optional[lsp4j.Range] = range.asJava

  def withRange(range: lsp4j.Range): HoverSignature =
    copy(range = Some(range))
}
