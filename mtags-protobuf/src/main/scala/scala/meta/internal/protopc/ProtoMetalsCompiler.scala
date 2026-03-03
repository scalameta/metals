package scala.meta.internal.protopc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import scala.meta.internal.io.FileIO
import scala.meta.internal.proto.binder.ImportResolver
import scala.meta.internal.proto.diag.SourceFile
import scala.meta.internal.proto.parse.Parser
import scala.meta.internal.proto.tree.Proto
import scala.meta.internal.proto.tree.Proto._
import scala.meta.io.AbsolutePath
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

  private lazy val bundledProtobufJavaProtos: Map[String, Path] =
    loadBundledProtobufJavaProtos()

  /**
   * Proto file registry that searches for proto files in import roots and jars.
   * This follows Turbine's approach - instead of requiring explicit configuration,
   * we search all known proto files to find imports automatically.
   *
   * This searches:
   *  1. Build import paths (directories and jar files)
   *  2. Bundled well-known types from protobuf-java discovered at runtime
   *
   * In the future, this should integrate with MbtWorkspaceSymbolProvider to search
   * all .proto files in the workspace.
   */
  private val protoFileRegistry: java.util.function.Function[String, Path] =
    (importPath: String) => {
      // Try import roots first (workspace/classpath), then bundled protobuf-java protos.
      val fromImportRoots = importPaths.iterator
        .flatMap { root =>
          findProtoInRoot(root, importPath)
        }
        .nextOption()
      fromImportRoots
        .orElse(findBundledProtobufJavaProto(importPath))
        .orNull
    }

  private def findProtoInRoot(root: Path, importPath: String): Option[Path] = {
    try {
      if (Files.isDirectory(root)) findProtoInTree(root, importPath)
      else if (isJarPath(root)) findProtoInJar(root, importPath)
      else None
    } catch {
      case NonFatal(e) =>
        logger.debug(
          s"Error searching for proto import $importPath in $root: ${e.getMessage}"
        )
        None
    }
  }

  private def findProtoInTree(root: Path, importPath: String): Option[Path] = {
    val directCandidate = root.resolve(importPath)
    if (Files.isRegularFile(directCandidate)) Some(directCandidate)
    else {
      val walker = Files.walk(root)
      try {
        walker
          .iterator()
          .asScala
          .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".proto"))
          .find(p => looksLikeImportPath(p, importPath))
      } finally {
        walker.close()
      }
    }
  }

  private def findProtoInJar(jarPath: Path, importPath: String): Option[Path] =
    try {
      FileIO.withJarFileSystem(
        AbsolutePath(jarPath),
        create = false,
        close = false
      ) { root =>
        findProtoInTree(root.toNIO, importPath)
      }
    } catch {
      case NonFatal(e) =>
        logger.debug(
          s"Error searching for proto import $importPath in jar $jarPath: ${e.getMessage}"
        )
        None
    }

  private def isJarPath(path: Path): Boolean =
    Files.isRegularFile(path) && path.getFileName.toString.endsWith(".jar")

  private def looksLikeImportPath(path: Path, importPath: String): Boolean = {
    val normalized = path.toString.replace('\\', '/')
    normalized.endsWith(importPath) || path.getFileName.toString == importPath
  }

  private def findBundledProtobufJavaProto(importPath: String): Option[Path] =
    bundledProtobufJavaProtos
      .get(importPath)
      .orElse {
        if (!importPath.contains('/')) {
          bundledProtobufJavaProtos.collectFirst {
            case (path, protoPath) if path.endsWith("/" + importPath) =>
              protoPath
          }
        } else None
      }

  private def loadBundledProtobufJavaProtos(): Map[String, Path] = {
    (for {
      jarPath <- protobufJavaJarPath()
      protos <-
        try {
          Some(
            FileIO.withJarFileSystem(
              AbsolutePath(jarPath),
              create = false,
              close = false
            ) { root =>
              val protos = FileIO
                .listAllFilesRecursively(root)
                .iterator
                .filter(path => path.isFile && path.toString.endsWith(".proto"))
                .map { path =>
                  val normalized = path.toString.replace('\\', '/')
                  normalized.stripPrefix("/") -> path.toNIO
                }
                .toMap
              if (protos.nonEmpty) {
                logger.debug(
                  s"Loaded ${protos.size} bundled .proto files from $jarPath"
                )
              } else {
                logger.debug(s"No bundled .proto files found in $jarPath")
              }
              protos
            }
          )
        } catch {
          case NonFatal(e) =>
            logger.debug(
              s"Failed loading bundled .proto files from protobuf-java: ${e.getMessage}"
            )
            None
        }
    } yield protos).getOrElse(Map.empty)
  }

  private def protobufJavaJarPath(): Option[Path] =
    classJarPath(classOf[com.google.protobuf.DescriptorProtos])
      .orElse(classJarPath(classOf[com.google.protobuf.Timestamp]))

  private def classJarPath(clazz: Class[_]): Option[Path] =
    for {
      protectionDomain <- Option(clazz.getProtectionDomain)
      codeSource <- Option(protectionDomain.getCodeSource)
      location <- Option(codeSource.getLocation)
      path <-
        try Some(Paths.get(location.toURI))
        catch {
          case NonFatal(_) => None
        }
      if Files.isRegularFile(path) && path.getFileName.toString.endsWith(".jar")
    } yield path

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
