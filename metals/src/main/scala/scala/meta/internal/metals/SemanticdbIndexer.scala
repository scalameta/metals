package scala.meta.internal.metals

import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

import scala.util.control.NonFatal

import scala.meta.internal.decorations.SyntheticsDecorationProvider
import scala.meta.internal.implementation.ImplementationProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.JavacOptionsResult
import ch.epfl.scala.bsp4j.ScalacOptionsResult
import com.google.protobuf.InvalidProtocolBufferException

class SemanticdbIndexer(
    referenceProvider: ReferenceProvider,
    implementationProvider: ImplementationProvider,
    implicitDecorator: SyntheticsDecorationProvider,
    buildTargets: BuildTargets,
    workspace: AbsolutePath
) {

  def onScalacOptions(scalacOptions: ScalacOptionsResult): Unit = {
    for {
      item <- scalacOptions.getItems.asScala
      scalaInfo <- buildTargets.scalaInfo(item.getTarget)
    } {
      val targetroot = item.targetroot(scalaInfo.getScalaVersion)
      onChangeDirectory(targetroot.resolve(Directories.semanticdb).toNIO)
    }
  }

  def onJavacOptions(javacOptions: JavacOptionsResult): Unit = {
    for {
      item <- javacOptions.getItems.asScala
    } {
      val targetroot = item.targetroot
      onChangeDirectory(targetroot.resolve(Directories.semanticdb).toNIO)
    }
  }

  def reset(): Unit = {
    referenceProvider.reset()
    implementationProvider.clear()
  }

  def onDelete(file: Path): Unit = {
    referenceProvider.onDelete(file)
    implementationProvider.onDelete(file)
  }

  private def onChangeDirectory(dir: Path): Unit = {
    if (Files.isDirectory(dir)) {
      val stream = Files.walk(dir)
      try {
        stream.forEach(onChange(_))
      } finally {
        stream.close()
      }
    }
  }

  def onChange(path: AbsolutePath, textDocument: TextDocument): Unit = {
    val docs = TextDocuments(Seq(textDocument))
    referenceProvider.onChange(docs, path)
    implementationProvider.onChange(docs, path)
    implicitDecorator.onChange(docs, path)
  }

  def onChange(file: Path): Unit = {
    if (!Files.isDirectory(file)) {
      if (file.isSemanticdb) {
        try {
          val doc = TextDocuments.parseFrom(Files.readAllBytes(file))
          SemanticdbClasspath.toScala(workspace, AbsolutePath(file)).foreach {
            scalaSourceFile =>
              referenceProvider.onChange(doc, scalaSourceFile)
              implementationProvider.onChange(doc, scalaSourceFile)
              implicitDecorator.onChange(doc, scalaSourceFile)
          }
        } catch {
          /* @note in some cases file might be created or modified, but not actually finished
           * being written. In that case, exception here is expected and a new event will
           * follow after it was finished.
           */
          case e: InvalidProtocolBufferException =>
            scribe.debug(s"$file is not yet ready", e)
          /* @note FileSystemException is thrown on Windows instead of InvalidProtocolBufferException */
          case e: FileSystemException =>
            scribe.debug(s"$file is not yet ready", e)
          case NonFatal(e) =>
            scribe.warn(s"unexpected error processing the file $file", e)
        }
      } else {
        scribe.warn(s"not a semanticdb file: $file")
      }
    }
  }
}
