package scala.meta.internal.protopc

import java.{util => ju}

import scala.jdk.CollectionConverters._

import scala.meta.internal.pc.SemanticTokens
import scala.meta.internal.pc.TokenNode
import scala.meta.internal.proto.tree.Proto._
import scala.meta.pc.Node
import scala.meta.pc.VirtualFileParams

import org.eclipse.lsp4j.SemanticTokenTypes

/**
 * Provides semantic tokens for Protobuf files.
 *
 * Maps proto constructs to semantic token types for syntax highlighting:
 * - Message/Enum names → Class
 * - Field names → Property
 * - Enum values → EnumMember
 * - Service names → Interface
 * - RPC method names → Method
 * - Type references → Type
 */
class ProtoSemanticTokensProvider(
    compiler: ProtoMetalsCompiler,
    params: VirtualFileParams
) {

  def semanticTokens(): ju.List[Node] = {
    compiler.parse(params) match {
      case Some(file) =>
        val tokens = scala.collection.mutable.ArrayBuffer.empty[Node]
        collectTokens(file, tokens)
        tokens.sortBy(_.start()).asJava
      case None =>
        ju.Collections.emptyList[Node]()
    }
  }

  private def collectTokens(
      file: ProtoFile,
      tokens: scala.collection.mutable.ArrayBuffer[Node]
  ): Unit = {
    // Collect tokens from all declarations
    file.declarations().asScala.foreach {
      case msg: MessageDecl => collectMessageTokens(msg, tokens)
      case enum: EnumDecl => collectEnumTokens(enum, tokens)
      case svc: ServiceDecl => collectServiceTokens(svc, tokens)
      case _ => // ignore other declarations
    }
  }

  private def collectMessageTokens(
      msg: MessageDecl,
      tokens: scala.collection.mutable.ArrayBuffer[Node]
  ): Unit = {
    // Message name
    addToken(msg.name(), SemanticTokenTypes.Class, tokens)

    // Fields
    msg.fields().asScala.foreach { field =>
      collectFieldTokens(field, tokens)
    }

    // Nested messages
    msg.nestedMessages().asScala.foreach { nested =>
      collectMessageTokens(nested, tokens)
    }

    // Nested enums
    msg.nestedEnums().asScala.foreach { nested =>
      collectEnumTokens(nested, tokens)
    }

    // Oneofs
    msg.oneofs().asScala.foreach { oneof =>
      collectOneofTokens(oneof, tokens)
    }

    // Map fields
    msg.mapFields().asScala.foreach { mapField =>
      collectMapFieldTokens(mapField, tokens)
    }
  }

  private def collectFieldTokens(
      field: FieldDecl,
      tokens: scala.collection.mutable.ArrayBuffer[Node]
  ): Unit = {
    // Field type (only for non-scalar types)
    collectTypeRefTokens(field.`type`(), tokens)

    // Field name
    addToken(field.name(), SemanticTokenTypes.Property, tokens)
  }

  private def collectTypeRefTokens(
      typeRef: TypeRef,
      tokens: scala.collection.mutable.ArrayBuffer[Node]
  ): Unit = {
    // Only highlight non-scalar types
    if (!typeRef.isScalar()) {
      typeRef.parts().asScala.foreach { part =>
        addToken(part, SemanticTokenTypes.Type, tokens)
      }
    }
  }

  private def collectEnumTokens(
      enum: EnumDecl,
      tokens: scala.collection.mutable.ArrayBuffer[Node]
  ): Unit = {
    // Enum name
    addToken(enum.name(), SemanticTokenTypes.Enum, tokens)

    // Enum values
    enum.values().asScala.foreach { value =>
      addToken(value.name(), SemanticTokenTypes.EnumMember, tokens)
    }
  }

  private def collectServiceTokens(
      svc: ServiceDecl,
      tokens: scala.collection.mutable.ArrayBuffer[Node]
  ): Unit = {
    // Service name
    addToken(svc.name(), SemanticTokenTypes.Interface, tokens)

    // RPC methods
    svc.rpcs().asScala.foreach { rpc =>
      collectRpcTokens(rpc, tokens)
    }
  }

  private def collectRpcTokens(
      rpc: RpcDecl,
      tokens: scala.collection.mutable.ArrayBuffer[Node]
  ): Unit = {
    // RPC method name
    addToken(rpc.name(), SemanticTokenTypes.Method, tokens)

    // Input and output types
    collectTypeRefTokens(rpc.inputType(), tokens)
    collectTypeRefTokens(rpc.outputType(), tokens)
  }

  private def collectOneofTokens(
      oneof: OneofDecl,
      tokens: scala.collection.mutable.ArrayBuffer[Node]
  ): Unit = {
    // Oneof name
    addToken(oneof.name(), SemanticTokenTypes.Property, tokens)

    // Oneof fields
    oneof.fields().asScala.foreach { field =>
      collectFieldTokens(field, tokens)
    }
  }

  private def collectMapFieldTokens(
      mapField: MapFieldDecl,
      tokens: scala.collection.mutable.ArrayBuffer[Node]
  ): Unit = {
    // Key and value types
    collectTypeRefTokens(mapField.keyType(), tokens)
    collectTypeRefTokens(mapField.valueType(), tokens)

    // Map field name
    addToken(mapField.name(), SemanticTokenTypes.Property, tokens)
  }

  private def addToken(
      ident: Ident,
      tokenType: String,
      tokens: scala.collection.mutable.ArrayBuffer[Node]
  ): Unit = {
    val typeId = SemanticTokens.getTypeId.getOrElse(tokenType, 0)
    tokens += TokenNode(
      ident.position(),
      ident.endPosition(),
      typeId,
      0
    )
  }
}
