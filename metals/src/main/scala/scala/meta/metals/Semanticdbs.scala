package scala.meta.metals

import com.google.protobuf.InvalidProtocolBufferException
import java.nio.file.Files
import scala.meta.interactive.InteractiveSemanticdb
import scala.meta.metals.compiler.ScalacProvider
import scala.meta.metals.compiler.CompilerEnrichments._
import scala.meta.parsers.ParseException
import scala.{meta => m}
import scala.meta.tokenizers.TokenizeException
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.reporters.StoreReporter
import scala.util.control.NonFatal
import scala.{meta => m}
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.inputs.Input
import org.langmeta.internal.io.PathIO
import scala.meta.internal.semanticdb3
import org.langmeta.io.AbsolutePath
import org.langmeta.io.RelativePath
import scala.meta.internal.semanticdb3.SymbolOccurrence

object Semanticdbs extends LazyLogging {

  object File {
    def unapply(path: RelativePath): Boolean =
      PathIO.extension(path.toNIO) == "semanticdb"
  }

  def toSemanticdb(
      input: Input.VirtualFile,
      scalacProvider: ScalacProvider
  ): Option[m.Database] =
    for {
      compiler <- scalacProvider.getCompiler(input)
    } yield toSemanticdb(input, compiler)

  def toSemanticdb(
      input: Input.VirtualFile,
      compiler: Global,
  ): m.Database = {
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
    m.Database(doc.copy(language = "Scala212") :: Nil)
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
    m.Document(input, "", Nil, messages, Nil, Nil)
  }

  implicit private val occurrenceOrdering: Ordering[SymbolOccurrence] =
    new Ordering[semanticdb3.SymbolOccurrence] {
      override def compare(x: SymbolOccurrence, y: SymbolOccurrence): Int = {
        if (x.range.isEmpty) 0
        else if (y.range.isEmpty) 0
        else {
          val a = x.range.get
          val b = y.range.get
          val byLine = Integer.compare(
            a.startLine,
            b.startLine
          )
          if (byLine != 0) {
            byLine
          } else {
            val byCharacter = Integer.compare(
              a.startCharacter,
              b.startCharacter
            )
            byCharacter
          }
        }
      }
    }

  def loadFromFile(
      semanticdbPath: AbsolutePath,
      cwd: AbsolutePath
  ): semanticdb3.TextDocuments = {
    val bytes = Files.readAllBytes(semanticdbPath.toNIO)
    val sdb =
      try {
        semanticdb3.TextDocuments.parseFrom(bytes)
      } catch {
        case _: InvalidProtocolBufferException =>
          logger.error(
            s"Have you upgraded to SemanticDB v3? Error parsing $semanticdbPath"
          )
          semanticdb3.TextDocuments()
      }

    val docs = sdb.documents.map { d =>
      val filename = cwd.resolve(d.uri).toURI.toString
      logger.info(s"Loading file $filename")
      d.withUri(filename)
        .withOccurrences {
          // This should be done inside semanticdb-scalac.
          val names = d.occurrences.toArray
          util.Sorting.quickSort(names)
          names
        }
    }
    semanticdb3.TextDocuments(docs)
  }

}
