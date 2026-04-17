package scala.meta.internal.protopc

import java.net.URI
import java.nio.file.Paths

import scala.jdk.CollectionConverters._

import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.proto.binder.Binder
import scala.meta.internal.proto.binder.ImportResolver
import scala.meta.internal.proto.binder.sym.Symbol
import scala.meta.internal.proto.tree.Proto._

/**
 * Generates SemanticDB TextDocument from a parsed proto file.
 *
 * This is used for indexing and semantic highlighting.
 */
class ProtoSemanticdbProvider(
    uri: URI,
    text: String,
    file: ProtoFile,
    importResolver: ImportResolver
) {
  private val sourcePath: java.nio.file.Path =
    try Paths.get(uri)
    catch {
      case _: Exception => null
    }

  private val bindingResult =
    Binder.bindWithErrors(file, importResolver, sourcePath)
  private val symbolTable = bindingResult.symbolTable()

  private def semanticdbSymbol(sym: Symbol): String = {
    if (sym == null) return ""
    // Use the Java Symbol.semanticdbSymbol() method which correctly
    // includes the package prefix using fullName()
    sym.semanticdbSymbol()
  }

  def textDocument(): Semanticdb.TextDocument = {
    val builder = Semanticdb.TextDocument
      .newBuilder()
      .setSchema(Semanticdb.Schema.SEMANTICDB4)
      .setUri(uri.toString)
      .setText(text)
      .setLanguage(Semanticdb.Language.UNKNOWN_LANGUAGE)

    // Collect occurrences from all declarations
    file.declarations().asScala.foreach {
      case msg: MessageDecl => collectMessage(msg, builder)
      case enum: EnumDecl => collectEnum(enum, builder)
      case svc: ServiceDecl => collectService(svc, builder)
      case _ => // ignore
    }

    builder.build()
  }

  private def collectMessage(
      msg: MessageDecl,
      builder: Semanticdb.TextDocument.Builder
  ): Unit = {
    val sym = symbolTable.lookup(msg)
    if (sym == null) return
    val symbol = semanticdbSymbol(sym)

    // Definition occurrence for the message name
    builder.addOccurrences(
      Semanticdb.SymbolOccurrence
        .newBuilder()
        .setRange(identToRange(msg.name()))
        .setSymbol(symbol)
        .setRole(Semanticdb.SymbolOccurrence.Role.DEFINITION)
        .build()
    )

    // Symbol information
    builder.addSymbols(
      Semanticdb.SymbolInformation
        .newBuilder()
        .setSymbol(symbol)
        .setLanguage(Semanticdb.Language.UNKNOWN_LANGUAGE)
        .setKind(Semanticdb.SymbolInformation.Kind.CLASS)
        .setDisplayName(msg.name().value())
        .build()
    )

    // Fields
    msg.fields().asScala.foreach { field =>
      collectField(field, sym, builder)
    }

    // Nested messages
    msg.nestedMessages().asScala.foreach { nested =>
      collectMessage(nested, builder)
    }

    // Nested enums
    msg.nestedEnums().asScala.foreach { nested =>
      collectEnum(nested, builder)
    }

    // Oneofs
    msg.oneofs().asScala.foreach { oneof =>
      collectOneof(oneof, sym, builder)
    }

    // Map fields
    msg.mapFields().asScala.foreach { mapField =>
      collectMapField(mapField, sym, builder)
    }
  }

  private def collectField(
      field: FieldDecl,
      owner: Symbol,
      builder: Semanticdb.TextDocument.Builder
  ): Unit = {
    val sym = symbolTable.lookup(field)
    if (sym == null) return
    val symbol = semanticdbSymbol(sym)

    // Definition occurrence for the field name
    builder.addOccurrences(
      Semanticdb.SymbolOccurrence
        .newBuilder()
        .setRange(identToRange(field.name()))
        .setSymbol(symbol)
        .setRole(Semanticdb.SymbolOccurrence.Role.DEFINITION)
        .build()
    )

    // Symbol information
    builder.addSymbols(
      Semanticdb.SymbolInformation
        .newBuilder()
        .setSymbol(symbol)
        .setLanguage(Semanticdb.Language.UNKNOWN_LANGUAGE)
        .setKind(Semanticdb.SymbolInformation.Kind.FIELD)
        .setDisplayName(field.name().value())
        .setProperties(Semanticdb.SymbolInformation.Property.VAL_VALUE)
        .build()
    )

    // Type reference (if not scalar)
    if (!field.`type`().isScalar()) {
      collectTypeRef(field.`type`(), owner, builder)
    }
  }

  private def collectTypeRef(
      typeRef: TypeRef,
      owner: Symbol,
      builder: Semanticdb.TextDocument.Builder
  ): Unit = {
    val resolved = symbolTable.resolveType(typeRef.fullName(), owner)
    if (resolved == null) return
    val resolvedSymbol = semanticdbSymbol(resolved)
    // Add reference occurrence for each part of the type
    typeRef.parts().asScala.foreach { part =>
      builder.addOccurrences(
        Semanticdb.SymbolOccurrence
          .newBuilder()
          .setRange(identToRange(part))
          .setSymbol(resolvedSymbol)
          .setRole(Semanticdb.SymbolOccurrence.Role.REFERENCE)
          .build()
      )
    }
  }

  private def collectEnum(
      enum: EnumDecl,
      builder: Semanticdb.TextDocument.Builder
  ): Unit = {
    val sym = symbolTable.lookup(enum)
    if (sym == null) return
    val symbol = semanticdbSymbol(sym)

    // Definition occurrence for the enum name
    builder.addOccurrences(
      Semanticdb.SymbolOccurrence
        .newBuilder()
        .setRange(identToRange(enum.name()))
        .setSymbol(symbol)
        .setRole(Semanticdb.SymbolOccurrence.Role.DEFINITION)
        .build()
    )

    // Symbol information
    builder.addSymbols(
      Semanticdb.SymbolInformation
        .newBuilder()
        .setSymbol(symbol)
        .setLanguage(Semanticdb.Language.UNKNOWN_LANGUAGE)
        .setKind(Semanticdb.SymbolInformation.Kind.CLASS)
        .setDisplayName(enum.name().value())
        .build()
    )

    // Enum values
    enum.values().asScala.foreach { value =>
      collectEnumValue(value, builder)
    }
  }

  private def collectEnumValue(
      value: EnumValueDecl,
      builder: Semanticdb.TextDocument.Builder
  ): Unit = {
    val sym = symbolTable.lookup(value)
    if (sym == null) return
    val symbol = semanticdbSymbol(sym)

    // Definition occurrence for the value name
    builder.addOccurrences(
      Semanticdb.SymbolOccurrence
        .newBuilder()
        .setRange(identToRange(value.name()))
        .setSymbol(symbol)
        .setRole(Semanticdb.SymbolOccurrence.Role.DEFINITION)
        .build()
    )

    // Symbol information
    builder.addSymbols(
      Semanticdb.SymbolInformation
        .newBuilder()
        .setSymbol(symbol)
        .setLanguage(Semanticdb.Language.UNKNOWN_LANGUAGE)
        .setKind(Semanticdb.SymbolInformation.Kind.FIELD)
        .setDisplayName(value.name().value())
        .setProperties(Semanticdb.SymbolInformation.Property.VAL_VALUE)
        .build()
    )
  }

  private def collectService(
      svc: ServiceDecl,
      builder: Semanticdb.TextDocument.Builder
  ): Unit = {
    val sym = symbolTable.lookup(svc)
    if (sym == null) return
    val symbol = semanticdbSymbol(sym)

    // Definition occurrence for the service name
    builder.addOccurrences(
      Semanticdb.SymbolOccurrence
        .newBuilder()
        .setRange(identToRange(svc.name()))
        .setSymbol(symbol)
        .setRole(Semanticdb.SymbolOccurrence.Role.DEFINITION)
        .build()
    )

    // Symbol information
    builder.addSymbols(
      Semanticdb.SymbolInformation
        .newBuilder()
        .setSymbol(symbol)
        .setLanguage(Semanticdb.Language.UNKNOWN_LANGUAGE)
        .setKind(Semanticdb.SymbolInformation.Kind.INTERFACE)
        .setDisplayName(svc.name().value())
        .build()
    )

    // RPC methods
    svc.rpcs().asScala.foreach { rpc =>
      collectRpc(rpc, sym, builder)
    }
  }

  private def collectRpc(
      rpc: RpcDecl,
      owner: Symbol,
      builder: Semanticdb.TextDocument.Builder
  ): Unit = {
    val sym = symbolTable.lookup(rpc)
    if (sym == null) return
    val symbol = semanticdbSymbol(sym)

    // Definition occurrence for the RPC name
    builder.addOccurrences(
      Semanticdb.SymbolOccurrence
        .newBuilder()
        .setRange(identToRange(rpc.name()))
        .setSymbol(symbol)
        .setRole(Semanticdb.SymbolOccurrence.Role.DEFINITION)
        .build()
    )

    // Symbol information
    builder.addSymbols(
      Semanticdb.SymbolInformation
        .newBuilder()
        .setSymbol(symbol)
        .setLanguage(Semanticdb.Language.UNKNOWN_LANGUAGE)
        .setKind(Semanticdb.SymbolInformation.Kind.METHOD)
        .setDisplayName(rpc.name().value())
        .build()
    )

    // Input and output type references
    if (!rpc.inputType().isScalar()) {
      collectTypeRef(rpc.inputType(), owner, builder)
    }
    if (!rpc.outputType().isScalar()) {
      collectTypeRef(rpc.outputType(), owner, builder)
    }
  }

  private def collectOneof(
      oneof: OneofDecl,
      owner: Symbol,
      builder: Semanticdb.TextDocument.Builder
  ): Unit = {
    val sym = symbolTable.lookup(oneof)
    if (sym == null) return
    val symbol = semanticdbSymbol(sym)

    // Definition occurrence for the oneof name
    builder.addOccurrences(
      Semanticdb.SymbolOccurrence
        .newBuilder()
        .setRange(identToRange(oneof.name()))
        .setSymbol(symbol)
        .setRole(Semanticdb.SymbolOccurrence.Role.DEFINITION)
        .build()
    )

    // Symbol information
    builder.addSymbols(
      Semanticdb.SymbolInformation
        .newBuilder()
        .setSymbol(symbol)
        .setLanguage(Semanticdb.Language.UNKNOWN_LANGUAGE)
        .setKind(Semanticdb.SymbolInformation.Kind.FIELD)
        .setDisplayName(oneof.name().value())
        .build()
    )

    // Oneof fields
    oneof.fields().asScala.foreach { field =>
      collectField(field, owner, builder)
    }
  }

  private def collectMapField(
      mapField: MapFieldDecl,
      owner: Symbol,
      builder: Semanticdb.TextDocument.Builder
  ): Unit = {
    val sym = symbolTable.lookup(mapField)
    if (sym == null) return
    val symbol = semanticdbSymbol(sym)

    // Definition occurrence for the map field name
    builder.addOccurrences(
      Semanticdb.SymbolOccurrence
        .newBuilder()
        .setRange(identToRange(mapField.name()))
        .setSymbol(symbol)
        .setRole(Semanticdb.SymbolOccurrence.Role.DEFINITION)
        .build()
    )

    // Symbol information
    builder.addSymbols(
      Semanticdb.SymbolInformation
        .newBuilder()
        .setSymbol(symbol)
        .setLanguage(Semanticdb.Language.UNKNOWN_LANGUAGE)
        .setKind(Semanticdb.SymbolInformation.Kind.FIELD)
        .setDisplayName(mapField.name().value())
        .setProperties(Semanticdb.SymbolInformation.Property.VAL_VALUE)
        .build()
    )

    // Value type reference (if not scalar)
    if (!mapField.valueType().isScalar()) {
      collectTypeRef(mapField.valueType(), owner, builder)
    }
  }

  private def identToRange(ident: Ident): Semanticdb.Range = {
    val startLine = offsetToLine(ident.position())
    val startCol = offsetToColumn(ident.position())
    val endLine = offsetToLine(ident.endPosition())
    val endCol = offsetToColumn(ident.endPosition())
    Semanticdb.Range
      .newBuilder()
      .setStartLine(startLine)
      .setStartCharacter(startCol)
      .setEndLine(endLine)
      .setEndCharacter(endCol)
      .build()
  }

  private def offsetToLine(offset: Int): Int = {
    var line = 0
    var i = 0
    while (i < offset && i < text.length()) {
      if (text.charAt(i) == '\n') line += 1
      i += 1
    }
    line
  }

  private def offsetToColumn(offset: Int): Int = {
    var col = 0
    var i = offset - 1
    while (i >= 0 && text.charAt(i) != '\n') {
      col += 1
      i -= 1
    }
    col
  }
}
