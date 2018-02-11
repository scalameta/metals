package scala.meta.languageserver

import java.nio.file.Files
import scala.meta.interactive.InteractiveSemanticdb
import scala.meta.languageserver.compiler.ScalacProvider
import scala.meta.languageserver.compiler.CompilerEnrichments._
import scala.meta.parsers.ParseException
import scala.meta.semanticdb
import scala.meta.tokenizers.TokenizeException
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.reporters.StoreReporter
import scala.util.control.NonFatal
import scala.{meta => m}
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.inputs.Input
import scala.meta.internal.semanticdb3.TextDocuments
import org.langmeta.io.AbsolutePath

object Semanticdbs extends LazyLogging {

  def toSemanticdb(
      input: Input.VirtualFile,
      scalacProvider: ScalacProvider
  ): Option[semanticdb.Database] =
    for {
      compiler <- scalacProvider.getCompiler(input)
    } yield toSemanticdb(input, compiler)

  def toSemanticdb(
      input: Input.VirtualFile,
      compiler: Global,
  ): semanticdb.Database = {
    val doc = try {
      InteractiveSemanticdb.toDocument(
        compiler = compiler,
        code = input.value,
        filename = input.path,
        timeout = 10000
      )
    } catch {
      case NonFatal(err) =>
        err match {
          case _: ParseException | _: TokenizeException =>
          // ignore, expected.
          case _ =>
            logger.error(s"Failed to emit semanticdb for ${input.path}", err)
        }
        toMessageOnlySemanticdb(input, compiler)
    }
    semanticdb.Database(doc.copy(language = "Scala212") :: Nil)
  }

  def toMessageOnlySemanticdb(
      input: Input,
      compiler: Global
  ): m.Document = {
    val messages = compiler.reporter match {
      case r: StoreReporter =>
        r.infos.collect {
          case r.Info(pos, msg, severity) =>
            val mpos = pos.toMeta(input)
            val msev = severity match {
              case r.INFO => m.Severity.Info
              case r.WARNING => m.Severity.Warning
              case _ => m.Severity.Error
            }
            m.Message(mpos, msev, msg)
        }.toList
      case _ => Nil
    }
    semanticdb.Document(input, "", Nil, messages, Nil, Nil)
  }

  def loadFromFile(
      semanticdbPath: AbsolutePath,
      cwd: AbsolutePath
  ): TextDocuments = {
    val bytes = Files.readAllBytes(semanticdbPath.toNIO)
    val docs = TextDocuments.parseFrom(bytes)
    TextDocuments(
      docs.documents.map { d =>
        val uri = s"file:${cwd.resolve(d.uri)}"
        logger.info(s"Loading file $uri")
        d.withUri(uri)
          .withOccurrences {
            // This should be done inside semanticdb-scalac.
            val occs = d.occurrences.toArray
            util.Sorting.quickSort(occs)(
              Ordering.by(_.range.fold((-1, -1))(r => (r.startLine, r.startCharacter)))
            )
            occs
          }
      }
    )
  }

}
