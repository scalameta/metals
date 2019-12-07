package scala.meta.internal.worksheets

import mdoc.interfaces.EvaluatedWorksheet
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.io.AbsolutePath
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Hover

trait WorksheetPublisher {

  def publish(
      languageClient: MetalsLanguageClient,
      path: AbsolutePath,
      worksheet: EvaluatedWorksheet
  ): Unit

  def hover(path: AbsolutePath, position: Position): Option[Hover]

}
