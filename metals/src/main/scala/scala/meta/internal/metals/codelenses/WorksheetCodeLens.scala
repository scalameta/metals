package scala.meta.internal.metals.codelenses

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.ClientCommands.CopyWorksheetOutput
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

class WorksheetCodeLens(clientConfig: ClientConfiguration)(implicit
    val ec: ExecutionContext
) extends CodeLens {

  override def isEnabled: Boolean = clientConfig.isCopyWorksheetOutputProvider()

  override def codeLenses(
      path: AbsolutePath
  ): Future[Seq[l.CodeLens]] = Future {
    if (path.isWorksheet) {
      val command = CopyWorksheetOutput.toLsp(path.toURI)
      val startPosition = new l.Position(0, 0)
      val range = new l.Range(startPosition, startPosition)
      List(new l.CodeLens(range, command, null))
    } else {
      Nil
    }
  }

}
