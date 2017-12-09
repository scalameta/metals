package scala.meta.languageserver

import java.nio.file.Files
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.internal.semanticdb.schema.Database
import org.langmeta.io.AbsolutePath
import org.langmeta.internal.semanticdb._
import scala.meta.interactive.InteractiveSemanticdb
import scala.meta.parsers.ParseException
import scala.meta.semanticdb
import scala.tools.nsc.interactive.Global
import langserver.types.VersionedTextDocumentIdentifier
import ScalametaEnrichments._

object Semanticdbs extends LazyLogging {
  def loadFromTextDocument(
    compiler: Global,
    td: VersionedTextDocumentIdentifier,
    content: String,
    cwd: AbsolutePath
  ): Database = {
    val documents = try {
      List(InteractiveSemanticdb.toDocument(compiler, content, Uri.toPath(td.uri).get.toLanguageServerUri, 10000))
    } catch {
      case e: ParseException => Nil
    }
    semanticdb.Database(documents).toSchema(cwd)
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
