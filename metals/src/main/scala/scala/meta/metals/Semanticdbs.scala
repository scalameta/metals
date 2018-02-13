package scala.meta.metals

import java.nio.file.Files
import scala.meta.interactive.InteractiveSemanticdb
import scala.meta.metals.compiler.ScalacProvider
import scala.meta.metals.compiler.CompilerEnrichments._
import scala.meta.parsers.ParseException
import scala.meta.semanticdb
import scala.meta.tokenizers.TokenizeException
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.reporters.StoreReporter
import scala.util.control.NonFatal
import scala.{meta => m}
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.inputs.Input
import org.langmeta.internal.semanticdb.schema.Database
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
  ): Database = {
    val bytes = Files.readAllBytes(semanticdbPath.toNIO)
    val sdb = Database.parseFrom(bytes)
    Database(
      sdb.documents.map { d =>
        val filename = s"file:${cwd.resolve(d.filename)}"
        logger.info(s"Loading file $filename")
        d.withFilename(filename)
          .withNames {
            // This should be done inside semanticdb-scalac.
            val names = d.names.toArray
            util.Sorting.quickSort(names)(
              Ordering.by(_.position.fold(-1)(_.start))
            )
            names
          }
      }
    )
  }

}
