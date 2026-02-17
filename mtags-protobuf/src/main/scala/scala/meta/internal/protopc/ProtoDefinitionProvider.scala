package scala.meta.internal.protopc

import java.nio.file.Paths

import scala.jdk.CollectionConverters._

import scala.meta.internal.pc.DefinitionResultImpl
import scala.meta.internal.proto.binder.Binder
import scala.meta.internal.proto.binder.sym.EnumSymbol
import scala.meta.internal.proto.binder.sym.MessageSymbol
import scala.meta.internal.proto.binder.sym.Symbol
import scala.meta.internal.proto.tree.Proto
import scala.meta.internal.proto.tree.Proto._
import scala.meta.pc.DefinitionResult
import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * Provides go-to-definition for protobuf elements.
 */
class ProtoDefinitionProvider(
    compiler: ProtoMetalsCompiler,
    params: OffsetParams
) {

  def definition(): DefinitionResult = {
    val result = for {
      file <- compiler.parse(params)
      node <- compiler.nodeAtOffset(file, params.offset())
      defn <- findDefinition(file, node)
    } yield defn

    result.getOrElse(DefinitionResultImpl.empty)
  }

  private def findDefinition(
      file: ProtoFile,
      node: Proto
  ): Option[DefinitionResult] = {
    // Build symbol table with import resolution for cross-file navigation
    val sourcePath =
      try {
        Paths.get(new java.net.URI(params.uri().toString))
      } catch {
        case _: Exception => null
      }
    val bindingResult =
      Binder.bindWithErrors(file, compiler.importResolver, sourcePath)
    val symbolTable = bindingResult.symbolTable()

    node match {
      case importDecl: ImportDecl =>
        // Handle go-to-definition on import strings - jump to the imported file
        findImportedFile(importDecl, sourcePath).map { importedPath =>
          val uri = importedPath.toUri.toString
          val location = new Location(
            uri,
            new Range(new Position(0, 0), new Position(0, 0))
          )
          DefinitionResultImpl(
            s"file:${importedPath.getFileName}",
            java.util.Collections.singletonList(location)
          )
        }
      case typeRef: TypeRef =>
        // Find the definition of this type using the symbol table
        findTypeDefinitionWithSymbolTable(file, typeRef.fullName(), symbolTable)
          .map { case (defn, symOpt) =>
            val location = nodeToLocation(defn, symOpt)
            DefinitionResultImpl(
              semanticdbSymbol(defn, symbolTable),
              java.util.Collections.singletonList(location)
            )
          }
      case ident: Ident =>
        // First check if this ident is part of a type reference (to avoid shadowing by local declarations)
        findParentTypeRef(file, ident)
          .flatMap { typeRef =>
            findTypeDefinitionWithSymbolTable(
              file,
              typeRef.fullName(),
              symbolTable
            ).map { case (defn, symOpt) =>
              val location = nodeToLocation(defn, symOpt)
              DefinitionResultImpl(
                semanticdbSymbol(defn, symbolTable),
                java.util.Collections.singletonList(location)
              )
            }
          }
          .orElse {
            // Otherwise, check if this ident is the name of a declaration
            findDeclarationWithName(file, ident).map { decl =>
              val location = nodeToLocation(decl, None)
              DefinitionResultImpl(
                semanticdbSymbol(decl, symbolTable),
                java.util.Collections.singletonList(location)
              )
            }
          }
      case _ =>
        // For declarations, we're already at the definition
        Some(
          DefinitionResultImpl(
            semanticdbSymbol(node, symbolTable),
            java.util.Collections.singletonList(nodeToLocation(node, None))
          )
        )
    }
  }

  /** Find the imported file path using the import resolver. */
  private def findImportedFile(
      importDecl: ImportDecl,
      sourcePath: java.nio.file.Path
  ): Option[java.nio.file.Path] = {
    Option(compiler.importResolver.resolveImportPath(importDecl, sourcePath))
  }

  /** Find the declaration whose name is this identifier */
  private def findDeclarationWithName(
      file: ProtoFile,
      ident: Ident
  ): Option[Proto] = {
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
          // Check fields inside oneofs
          msg
            .oneofs()
            .asScala
            .flatMap(_.fields().asScala)
            .find(_.name() == ident)
            .map(f => f: Proto)
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

  /**
   * Find type definition using the symbol table (supports cross-file navigation).
   *  Returns both the AST node and its containing symbol (for source file info).
   */
  private def findTypeDefinitionWithSymbolTable(
      file: ProtoFile,
      typeName: String,
      symbolTable: scala.meta.internal.proto.binder.SymbolTable
  ): Option[(Proto, Option[Symbol])] = {
    // Try to resolve the type in the symbol table
    val symbol = symbolTable.resolveType(typeName, null)
    if (symbol != null) {
      // Get the AST node from the symbol
      symbol match {
        case msgSym: MessageSymbol => Some((msgSym.node(), Some(msgSym)))
        case enumSym: EnumSymbol => Some((enumSym.node(), Some(enumSym)))
        case _ => None
      }
    } else {
      // Fallback to local search in current file
      findTypeDefinitionLocal(file, typeName).map((_, None))
    }
  }

  /** Find type definition locally in the current file (original implementation). */
  private def findTypeDefinitionLocal(
      file: ProtoFile,
      typeName: String
  ): Option[Proto] = {
    // Search for a message or enum with this name
    def search(declarations: java.util.List[Proto]): Option[Proto] = {
      declarations.asScala.toList.flatMap {
        case m: MessageDecl if m.name().value() == typeName =>
          Some(m)
        case m: MessageDecl =>
          searchNested(m, typeName)
        case e: EnumDecl if e.name().value() == typeName =>
          Some(e)
        case _ => None
      }.headOption
    }

    def searchNested(msg: MessageDecl, name: String): Option[Proto] = {
      if (msg.name().value() == name) Some(msg)
      else {
        val fromNested = msg
          .nestedMessages()
          .asScala
          .toList
          .flatMap { nested =>
            if (nested.name().value() == name) Some(nested)
            else searchNested(nested, name)
          }
          .headOption

        fromNested.orElse(
          msg.nestedEnums().asScala.find(_.name().value() == name)
        )
      }
    }

    search(file.declarations())
  }

  private def findParentTypeRef(
      file: ProtoFile,
      ident: Ident
  ): Option[TypeRef] = {
    // Walk the AST to find if this ident is part of a TypeRef
    file
      .declarations()
      .asScala
      .iterator
      .flatMap {
        case m: MessageDecl => findTypeRefContaining(m, ident)
        case s: ServiceDecl => findTypeRefInService(s, ident)
        case _ => None
      }
      .nextOption()
  }

  private def findTypeRefInService(
      svc: ServiceDecl,
      ident: Ident
  ): Option[TypeRef] = {
    svc
      .rpcs()
      .asScala
      .flatMap { rpc =>
        if (containsPosition(rpc.inputType(), ident)) Some(rpc.inputType())
        else if (containsPosition(rpc.outputType(), ident))
          Some(rpc.outputType())
        else None
      }
      .headOption
  }

  private def findTypeRefContaining(
      msg: MessageDecl,
      ident: Ident
  ): Option[TypeRef] = {
    msg
      .fields()
      .asScala
      .collectFirst {
        case f if containsPosition(f.`type`(), ident) => f.`type`()
      }
      .orElse(
        // Check fields inside oneofs
        msg
          .oneofs()
          .asScala
          .flatMap(_.fields().asScala)
          .collectFirst {
            case f if containsPosition(f.`type`(), ident) => f.`type`()
          }
      )
      .orElse(
        msg
          .nestedMessages()
          .asScala
          .collectFirst { case nested =>
            findTypeRefContaining(nested, ident)
          }
          .flatten
      )
  }

  private def containsPosition(typeRef: TypeRef, ident: Ident): Boolean = {
    typeRef.position() <= ident.position() && ident.endPosition() <= typeRef
      .endPosition()
  }

  private def nodeToLocation(
      node: Proto,
      symbolOpt: Option[Symbol]
  ): Location = {
    // Convert node positions to LSP Location
    // For declarations with names, we want to highlight just the name, not the entire declaration
    val (startPos, endPos) = node match {
      case m: MessageDecl => (m.name().position(), m.name().endPosition())
      case e: EnumDecl => (e.name().position(), e.name().endPosition())
      case s: ServiceDecl => (s.name().position(), s.name().endPosition())
      case f: FieldDecl => (f.name().position(), f.name().endPosition())
      case v: EnumValueDecl => (v.name().position(), v.name().endPosition())
      case r: RpcDecl => (r.name().position(), r.name().endPosition())
      case o: OneofDecl => (o.name().position(), o.name().endPosition())
      case m: MapFieldDecl => (m.name().position(), m.name().endPosition())
      case _ => (node.position(), node.endPosition())
    }

    // Determine which file the node is from (current file or imported file)
    val (uri, text) = symbolOpt
      .flatMap { sym =>
        Option(sym.sourcePath()).map { path =>
          (
            path.toUri.toString,
            Option(sym.sourceText()).getOrElse(params.text())
          )
        }
      }
      .getOrElse((params.uri().toString, params.text()))

    val startLine = offsetToLine(startPos, text)
    val startCol = offsetToColumn(startPos, text)
    val endLine = offsetToLine(endPos, text)
    val endCol = offsetToColumn(endPos, text)

    new Location(
      uri,
      new Range(
        new Position(startLine, startCol),
        new Position(endLine, endCol)
      )
    )
  }

  private def offsetToLine(offset: Int, text: String): Int = {
    var line = 0
    var i = 0
    while (i < offset && i < text.length()) {
      if (text.charAt(i) == '\n') line += 1
      i += 1
    }
    line
  }

  private def offsetToColumn(offset: Int, text: String): Int = {
    var col = 0
    var i = offset - 1
    while (i >= 0 && text.charAt(i) != '\n') {
      col += 1
      i -= 1
    }
    col
  }

  private def semanticdbSymbol(
      node: Proto,
      symbolTable: scala.meta.internal.proto.binder.SymbolTable
  ): String = {
    // Look up the symbol to get its full qualified name (includes package)
    val symbol = symbolTable.lookup(node)

    if (symbol != null) {
      // SemanticDB symbol format:
      // - Packages: dots become slashes (my.pkg -> my/pkg/)
      // - Types: package/TypeName#
      // - Fields/values: just the name with . suffix (not fully qualified)
      // - RPCs: name with (). suffix
      node match {
        case _: MessageDecl =>
          val fullName = symbol.fullName()
          s"${fullName.replace(".", "/")}#"
        case _: EnumDecl =>
          val fullName = symbol.fullName()
          s"${fullName.replace(".", "/")}#"
        case _: ServiceDecl =>
          val fullName = symbol.fullName()
          s"${fullName.replace(".", "/")}#"
        case f: FieldDecl =>
          s"${f.name().value()}."
        case v: EnumValueDecl =>
          s"${v.name().value()}."
        case r: RpcDecl =>
          s"${r.name().value()}()."
        case o: OneofDecl =>
          s"${o.name().value()}."
        case _ => ""
      }
    } else {
      // If we can't find the symbol, return empty
      ""
    }
  }
}
