package scala.meta.internal.worksheets

import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.io.AbsolutePath

import mdoc.interfaces.EvaluatedWorksheet
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Position

trait WorksheetPublisher {

  def publish(
      languageClient: MetalsLanguageClient,
      path: AbsolutePath,
      worksheet: EvaluatedWorksheet
  ): Unit

  def hover(path: AbsolutePath, position: Position): Option[Hover]

}
