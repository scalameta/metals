package scala.meta.internal.metals.mbt

import java.nio.file.Path
import java.util.concurrent.ConcurrentSkipListSet
import javax.tools.JavaFileObject

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Configs.ProtobufLspConfig
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.proto.codegen.java.JavaOutlineGenerator
import scala.meta.internal.proto.diag.{SourceFile => ProtoSourceFile}
import scala.meta.internal.proto.parse.{Parser => ProtoParser}
import scala.meta.io.AbsolutePath
import scala.meta.pc

import com.google.turbine.binder.ClassPath
import com.google.turbine.binder.sym.ClassSymbol
import org.eclipse.{lsp4j => l}

final class MbtProtobufWorkspaceSymbolProvider(
    buffers: Buffers,
    protobufLspConfig: () => ProtobufLspConfig,
    clearAllProtobufCaches: () => Unit,
) {

  def isJavaPackageIndexingEnabled: Boolean =
    protobufLspConfig().definition

  def listProtoJavaOutlinesForPackage(
      pkg: String,
      documentsByPackage: TrieMap[String, ConcurrentSkipListSet[Path]],
      documents: TrieMap[AbsolutePath, IndexedDocument],
  ): Iterator[JavaFileObject] = {
    scribe.debug(s"mbt-v2-turbine: looking up proto outlines for package: $pkg")
    documentsByPackage.get(pkg) match {
      case None =>
        scribe.debug(s"mbt-v2-turbine: no documents found for package: $pkg")
        Iterator.empty
      case Some(paths) =>
        scribe.debug(
          s"mbt-v2-turbine: found ${paths.size()} files for package: $pkg"
        )
        for {
          path <- paths.asScala.iterator
          doc <- documents.get(AbsolutePath(path)).toList.iterator
          if doc.language.isProtobuf
          javaFile <- generateProtoJavaOutlines(doc, pkg)
        } yield javaFile
    }
  }

  private var isGrpcShaded: Boolean = false
  private val grpcShadedMessageSymbol = new ClassSymbol(
    "grpc_shaded/com/google/protobuf/Message"
  )
  def onNewProjectClasspath(classpath: ClassPath): Unit = {
    if (isGrpcShaded) return
    if (classpath.env().get(grpcShadedMessageSymbol) != null) {
      isGrpcShaded = true
      clearAllProtobufCaches()
    }
  }
  private def javaPackagePrefix(): String = {
    if (protobufLspConfig().javaPackagePrefix.nonEmpty) {
      protobufLspConfig().javaPackagePrefix
    } else if (isGrpcShaded) {
      // NOTE(olafurpg): This is not ideal because we can't guarantee the order
      // at which we get an onNewProjectClasspath callback to inform us that the
      // classpath needs to share Protobuf classes, and when we run protobuf
      // codegen.  However, this is directionally correct, we auto-infer the
      // setting based on the user's classpath so that it works out of the box
      // most of the time. This is still wrong if you want to support a mix of
      // shaded and unshaded Protobuf classes in the same workspace.
      "grpc_shaded."
    } else {
      ""
    }
  }

  def generateProtoJavaOutlines(
      doc: IndexedDocument,
      requestedPackage: String,
  ): Iterator[VirtualTextDocument] = {
    val packagePath = requestedPackage.stripSuffix("/").replace('/', '.')

    val allOutlines = doc.getOrComputeJavaOutlines(() =>
      try {
        val input = doc.file.toInputFromBuffers(buffers)
        val source = new ProtoSourceFile(input.path, input.text)
        val file = ProtoParser.parse(source)
        val generator = new JavaOutlineGenerator(
          javaPackagePrefix(),
          defaultOuterClassName(input.path),
        )
        val outputs = generator.generate(file)

        outputs.asScala.iterator.map { output =>
          val javaContent = output.content()
          val className = output.path().stripSuffix(".java").split('/').last
          val outputPackage = output
            .path()
            .stripSuffix(".java")
            .replace('/', '.')
            .split('.')
            .dropRight(1)
            .mkString(".")
          val fullClassName =
            if (outputPackage.nonEmpty) s"$outputPackage.$className"
            else className
          val pkg =
            if (outputPackage.nonEmpty) outputPackage.replace('.', '/') + "/"
            else ""

          scribe.debug(
            s"mbt-v2: generated Java outline for $fullClassName from ${doc.file}"
          )
          val virtualUri = ProtoJavaVirtualFile.makeUri(doc.file, className)
          VirtualTextDocument(
            virtualUri,
            pc.Language.JAVA,
            javaContent,
            Seq(pkg),
            Seq(fullClassName.replace('.', '/') + "#"),
          )
        }.toSeq
      } catch {
        case NonFatal(e) =>
          scribe.warn(
            s"Failed to generate Java outline for proto ${doc.file}",
            e,
          )
          Seq.empty
      }
    )

    allOutlines.iterator.filter { outline =>
      outline.packages.headOption
        .map(_.stripSuffix("/").replace('/', '.'))
        .contains(packagePath)
    }
  }

  def findProtoRpcDefinition(
      methodSymbol: String,
      documents: TrieMap[AbsolutePath, IndexedDocument],
  ): List[l.Location] = {
    import scala.meta.internal.jsemanticdb.Semanticdb
    import scala.meta.internal.mtags.proto.ProtoMtagsV2

    try {
      val sym = Symbol(methodSymbol)
      if (!sym.isMethod) return Nil

      val methodName = sym.displayName
      val rpcName =
        if (methodName.nonEmpty) methodName.head.toUpper + methodName.tail
        else ""

      val stubClassName = sym.owner.displayName
      val serviceName = stubClassName
        .stripSuffix("ImplBase")
        .stripSuffix("BlockingStub")
        .stripSuffix("FutureStub")
        .stripSuffix("Stub")

      (for {
        (path, doc) <- documents.iterator
        if doc.language.isProtobuf
        if doc.symbols.exists { symInfo =>
          symInfo.getKind == Semanticdb.SymbolInformation.Kind.INTERFACE &&
          symInfo.getSymbol.endsWith(s"$serviceName#")
        }
      } yield {
        val input = path.toInputFromBuffers(buffers)
        val protoMtags = new ProtoMtagsV2(input, includeMembers = true)
        val protoDoc = protoMtags.index()
        val rpcSuffix = s"$rpcName()."
        protoDoc.occurrences.iterator.flatMap { occ =>
          if (
            occ.symbol.endsWith(rpcSuffix) &&
            occ.role == scala.meta.internal.semanticdb.SymbolOccurrence.Role.DEFINITION
          ) {
            occ.range.map(range =>
              new l.Location(path.toURI.toString(), range.toLsp)
            )
          } else {
            None
          }
        }
      }).flatten.toList
    } catch {
      case NonFatal(e) =>
        scribe.debug(
          s"Failed to find proto RPC for $methodSymbol: ${e.getMessage}"
        )
        Nil
    }
  }

  private def defaultOuterClassName(protoPath: String): String = {
    val filename =
      protoPath.split('/').lastOption.getOrElse("").stripSuffix(".proto")
    val camel = filename
      .split('_')
      .iterator
      .filter(_.nonEmpty)
      .map(part => part.head.toUpper + part.tail)
      .mkString
    if (camel.nonEmpty) camel else "OuterClass"
  }
}
