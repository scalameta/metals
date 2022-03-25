package scala.meta.internal.mtags

import java.nio.charset.Charset
import java.nio.file.Files

import scala.meta.AbsolutePath
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.io.FileIO
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.SymbolOccurrenceOrdering._
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.XtensionSemanticdbSymbolInformation
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.RelativePath

trait Semanticdbs {
  def textDocument(path: AbsolutePath): TextDocumentLookup
}
object Semanticdbs {
  def loadTextDocuments(path: AbsolutePath): s.TextDocuments = {
    val in = Files.newInputStream(path.toNIO)
    try s.TextDocuments.parseFrom(in)
    finally in.close()
  }

  def loadTextDocument(
      scalaOrJavaPath: AbsolutePath,
      sourceroot: AbsolutePath,
      charset: Charset,
      fingerprints: Md5Fingerprints,
      loader: RelativePath => Option[FoundSemanticDbPath],
      log: String => Unit = (_) => ()
  ): TextDocumentLookup = {
    if (scalaOrJavaPath.toNIO.getFileSystem != sourceroot.toNIO.getFileSystem) {
      TextDocumentLookup.NotFound(scalaOrJavaPath)
    } else {
      val scalaRelativePath = scalaOrJavaPath.toRelative(sourceroot.dealias)
      val semanticdbRelativePath =
        SemanticdbClasspath.fromScalaOrJava(scalaRelativePath)
      loader(semanticdbRelativePath) match {
        case None =>
          TextDocumentLookup.NotFound(scalaOrJavaPath)
        case Some(semanticdbPath) =>
          loadResolvedTextDocument(
            scalaOrJavaPath,
            semanticdbPath.nonDefaultRelPath.getOrElse(scalaRelativePath),
            semanticdbPath.path,
            charset,
            fingerprints,
            log
          )
      }
    }
  }

  private def loadResolvedTextDocument(
      scalaPath: AbsolutePath,
      scalaRelativePath: RelativePath,
      semanticdbPath: AbsolutePath,
      charset: Charset,
      fingerprints: Md5Fingerprints,
      log: String => Unit
  ): TextDocumentLookup = {
    val reluri = scalaRelativePath.toURI(false).toString
    val sdocs = loadTextDocuments(semanticdbPath)
    sdocs.documents.find(_.uri.replace("\\", "/") == reluri) match {
      case None => TextDocumentLookup.NoMatchingUri(scalaPath, sdocs)
      case Some(sdoc) =>
        val text = FileIO.slurp(scalaPath, charset)
        val md5 = MD5.compute(text)
        val sdocMd5 = sdoc.md5.toUpperCase()
        if (sdocMd5 != md5) {
          fingerprints.lookupText(scalaPath, sdocMd5) match {
            case Some(oldText) =>
              TextDocumentLookup.Stale(scalaPath, md5, sdoc.withText(oldText))
            case None =>
              log(s"Could not load snapshot text for $scalaPath")
              TextDocumentLookup.Stale(scalaPath, md5, sdoc)
          }
        } else {
          TextDocumentLookup.Success(sdoc.withText(text))
        }
    }
  }
  def printTextDocument(doc: s.TextDocument): String = {
    val symtab = doc.symbols.iterator.map(info => info.symbol -> info).toMap
    val sb = new StringBuilder
    val occurrences = doc.occurrences.sorted
    val input = Input.String(doc.text)
    var offset = 0
    occurrences.foreach { occ =>
      val range = occ.range.get
      val pos = Position.Range(
        input,
        range.startLine,
        range.startCharacter,
        range.endLine,
        range.endCharacter
      )
      sb.append(doc.text.substring(offset, pos.end))
      val isPrimaryConstructor =
        symtab.get(occ.symbol).exists(_.isPrimary)
      if (!occ.symbol.isPackage && !isPrimaryConstructor) {
        printSymbol(sb, occ.symbol)
      }
      offset = pos.end
    }
    sb.append(doc.text.substring(offset))
    sb.toString()
  }

  def printSymbol(sb: StringBuilder, symbol: String): Unit = {
    sb.append("/*")
      // replace package / with dot . to not upset GitHub syntax highlighting.
      .append(symbol.replace('/', '.'))
      .append("*/")
  }

  case class FoundSemanticDbPath(
      path: AbsolutePath,
      nonDefaultRelPath: Option[RelativePath]
  )
}
