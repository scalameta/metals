package scala.meta.internal.worksheets

import mdoc.interfaces.EvaluatedWorksheet
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.io.AbsolutePath

trait WorksheetPublisher {

    def publish(languageClient: MetalsLanguageClient, path: AbsolutePath, worksheet: EvaluatedWorksheet): Unit

}