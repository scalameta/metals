package scala.meta.internal.protopc

import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import scala.meta.internal.proto.binder.ImportResolver
import scala.meta.internal.proto.diag.SourceFile
import scala.meta.internal.proto.parse.Parser
import scala.meta.internal.proto.tree.Proto
import scala.meta.internal.proto.tree.Proto._
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.VirtualFileParams

import org.slf4j.Logger

/**
 * Wraps the Java protobuf parser and provides parsing/analysis functionality.
 */
class ProtoMetalsCompiler(
    val buildTargetId: String,
    val logger: Logger,
    val importPaths: Seq[Path],
    val config: PresentationCompilerConfig
) {

  /**
   * Proto file registry that searches for proto files in import paths.
   * This follows Turbine's approach - instead of requiring explicit configuration,
   * we search all known proto files to find imports automatically.
   *
   * For now, this searches the import paths. In the future, this should integrate
   * with MbtWorkspaceSymbolProvider to search all .proto files in the workspace.
   */
  private val protoFileRegistry: java.util.function.Function[String, Path] =
    (importPath: String) => {
      // Try to find a file whose path ends with the import path
      importPaths.iterator
        .flatMap { root =>
          try {
            if (Files.isDirectory(root)) {
              // Walk the directory tree to find all .proto files
              val walker = Files.walk(root)
              try {
                walker
                  .filter(p =>
                    Files.isRegularFile(p) && p.toString.endsWith(".proto")
                  )
                  .iterator()
                  .asScala
                  .find { p =>
                    // Check if this file's path ends with the import path
                    p.toString.endsWith(
                      importPath
                    ) || p.getFileName.toString == importPath
                  }
              } finally {
                walker.close()
              }
            } else None
          } catch {
            case NonFatal(e) =>
              logger.debug(
                s"Error searching for proto import $importPath in $root: ${e.getMessage}"
              )
              None
          }
        }
        .nextOption()
        .orNull
    }

  val importResolver: ImportResolver = new ImportResolver(protoFileRegistry)

  /** Parse a protobuf source file. */
  def parse(params: VirtualFileParams): Option[ProtoFile] = {
    try {
      val source = new SourceFile(params.uri().toString, params.text())
      Some(Parser.parse(source))
    } catch {
      case NonFatal(e) =>
        logger.debug(s"Failed to parse ${params.uri()}: ${e.getMessage}")
        None
    }
  }

  /** Find the AST node at a given offset. */
  def nodeAtOffset(file: ProtoFile, offset: Int): Option[Proto] = {
    val finder = new NodeAtOffsetFinder(offset)
    finder.find(file)
  }

  /** Find the enclosing declaration at a given offset. */
  def enclosingDeclaration(
      file: ProtoFile,
      offset: Int
  ): Option[Proto] = {
    val finder = new EnclosingDeclarationFinder(offset)
    finder.find(file)
  }
}

/**
 * Visitor that finds the smallest node containing an offset.
 */
class NodeAtOffsetFinder(offset: Int) {

  private var result: Option[Proto] = None

  def find(file: ProtoFile): Option[Proto] = {
    visitFile(file)
    result
  }

  private def visitFile(file: ProtoFile): Unit = {
    checkNode(file)
    file.syntax().ifPresent(s => checkNode(s))
    file.pkg().ifPresent(p => visitPackage(p))
    file.imports().forEach(i => checkNode(i))
    file.options().forEach(o => checkNode(o))
    file.declarations().forEach(d => visitDeclaration(d))
  }

  private def visitPackage(pkg: PackageDecl): Unit = {
    checkNode(pkg)
    pkg.parts().forEach(p => checkNode(p))
  }

  private def visitDeclaration(decl: Proto): Unit = {
    decl match {
      case m: MessageDecl => visitMessage(m)
      case e: EnumDecl => visitEnum(e)
      case s: ServiceDecl => visitService(s)
      case ext: ExtendDecl => visitExtend(ext)
      case _ => checkNode(decl)
    }
  }

  private def visitMessage(msg: MessageDecl): Unit = {
    checkNode(msg)
    checkNode(msg.name())
    msg.fields().forEach(f => visitField(f))
    msg.nestedMessages().forEach(m => visitMessage(m))
    msg.nestedEnums().forEach(e => visitEnum(e))
    msg.oneofs().forEach(o => visitOneof(o))
    msg.mapFields().forEach(m => visitMapField(m))
  }

  private def visitField(field: FieldDecl): Unit = {
    checkNode(field)
    visitTypeRef(field.`type`())
    checkNode(field.name())
  }

  private def visitTypeRef(typeRef: TypeRef): Unit = {
    checkNode(typeRef)
    typeRef.parts().forEach(p => checkNode(p))
  }

  private def visitEnum(enum: EnumDecl): Unit = {
    checkNode(enum)
    checkNode(enum.name())
    enum.values().forEach(v => visitEnumValue(v))
  }

  private def visitEnumValue(value: EnumValueDecl): Unit = {
    checkNode(value)
    checkNode(value.name())
  }

  private def visitService(svc: ServiceDecl): Unit = {
    checkNode(svc)
    checkNode(svc.name())
    svc.rpcs().forEach(r => visitRpc(r))
  }

  private def visitRpc(rpc: RpcDecl): Unit = {
    checkNode(rpc)
    checkNode(rpc.name())
    visitTypeRef(rpc.inputType())
    visitTypeRef(rpc.outputType())
  }

  private def visitOneof(oneof: OneofDecl): Unit = {
    checkNode(oneof)
    checkNode(oneof.name())
    oneof.fields().forEach(f => visitField(f))
  }

  private def visitMapField(mapField: MapFieldDecl): Unit = {
    checkNode(mapField)
    visitTypeRef(mapField.keyType())
    visitTypeRef(mapField.valueType())
    checkNode(mapField.name())
  }

  private def visitExtend(ext: ExtendDecl): Unit = {
    checkNode(ext)
    visitTypeRef(ext.extendee())
    ext.fields().forEach(f => visitField(f))
  }

  private def checkNode(node: Proto): Unit = {
    if (node.position() <= offset && offset < node.endPosition()) {
      // This node contains the offset
      // Keep the smallest node
      result match {
        case Some(existing)
            if (existing.endPosition() - existing.position()) <=
              (node.endPosition() - node.position()) =>
        // existing is smaller, keep it
        case _ =>
          result = Some(node)
      }
    }
  }
}

/**
 * Visitor that finds the enclosing declaration (message, enum, service) at an offset.
 */
class EnclosingDeclarationFinder(offset: Int) {

  private var result: Option[Proto] = None

  def find(file: ProtoFile): Option[Proto] = {
    file.declarations().forEach(d => visitDeclaration(d, None))
    result
  }

  private def visitDeclaration(decl: Proto, parent: Option[Proto]): Unit = {
    if (decl.position() <= offset && offset < decl.endPosition()) {
      result = Some(decl)
      decl match {
        case m: MessageDecl =>
          m.nestedMessages().forEach(nm => visitDeclaration(nm, Some(m)))
          m.nestedEnums().forEach(ne => visitDeclaration(ne, Some(m)))
        case _ =>
      }
    }
  }
}
