package scala.meta.languageserver

import java.nio.file.Files
import scala.meta.interactive.InteractiveSemanticdb
import scala.meta.languageserver.compiler.ScalacProvider
import scala.meta.parsers.ParseException
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.internal.semanticdb.schema.Database
import org.langmeta.io.AbsolutePath
import scala.meta.semanticdb
import scala.util.Try
import org.langmeta.inputs.Input

object Semanticdbs extends LazyLogging {
  def toSemanticdb(
      input: Input.VirtualFile,
      scalac: ScalacProvider
  ): Option[semanticdb.Database] =
    for {
      path <- Uri.unapply(input.path)
      compiler <- scalac.getCompiler(path)
      document <- Try {
        InteractiveSemanticdb.toDocument(
          compiler = compiler,
          code = input.value,
          filename = input.path,
          timeout = 10000
        )
      }.fold({
        case _: ParseException => None // expected
        case err =>
          logger.error(s"Failed to emit semanticdb for $path", err)
          None
      }, Option.apply)
    } yield semanticdb.Database(document.copy(language = "Scala212") :: Nil)

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
