package scala.meta.languageserver

import java.nio.file.Files
import org.langmeta.internal.semanticdb.schema.Database
import org.langmeta.io.AbsolutePath

object Semanticdbs {
  def loadFromFile(path: AbsolutePath)(implicit cwd: AbsolutePath): Database = {
    val bytes = Files.readAllBytes(path.toNIO)
    val sdb = Database.parseFrom(bytes)
    Database(
      sdb.documents.map(
        d =>
          d.withFilename(s"file:${cwd.resolve(d.filename)}")
            .withNames {
              // This should be done inside semanticdb-scalac.
              val names = d.names.toArray
              util.Sorting.quickSort(names)(
                Ordering.by(_.position.fold(-1)(_.start))
              )
              names
          }
      )
    )
  }

}
