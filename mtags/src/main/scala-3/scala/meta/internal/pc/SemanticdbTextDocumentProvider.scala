package scala.meta.internal.pc

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

import scala.meta.internal.mtags.MD5
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.io.AbsolutePath

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.semanticdb.ExtractSemanticDB
import dotty.tools.dotc.semanticdb.Language
import dotty.tools.dotc.semanticdb.Schema
import dotty.tools.dotc.semanticdb.TextDocument
import dotty.tools.dotc.semanticdb.internal.SemanticdbOutputStream
import dotty.tools.dotc.util.SourceFile

class SemanticdbTextDocumentProvider(
    driver: MetalsDriver,
    workspace: Option[Path]
) extends WorksheetSemanticdbProvider {

  def textDocument(
      uri: URI,
      sourceCode: String
  ): Array[Byte] = {
    val filePath = Paths.get(uri)
    val validCode = removeMagicImports(sourceCode, AbsolutePath(filePath))
    val result = driver.run(
      uri,
      SourceFile.virtual(filePath.toString, validCode)
    )
    val extract = ExtractSemanticDB()
    val extractor = extract.Extractor()
    extractor.traverse(result.tree)(using result.context)
    val path = workspace
      .flatMap { workspacePath =>
        scala.util.Try(workspacePath.relativize(filePath)).toOption
      }
      .map { relativeUri =>
        relativeUri.toString()
      }
      .getOrElse(filePath.toString)

    val document = TextDocument(
      schema = Schema.SEMANTICDB4,
      language = Language.SCALA,
      uri = path,
      text = sourceCode,
      md5 = MD5.compute(sourceCode),
      symbols = extractor.symbolInfos.toList,
      occurrences = extractor.occurrences.toList
    )
    val byteStream = new ByteArrayOutputStream()
    val out = SemanticdbOutputStream.newInstance(byteStream)
    document.writeTo(out)
    out.flush()
    byteStream.toByteArray
  }
}
